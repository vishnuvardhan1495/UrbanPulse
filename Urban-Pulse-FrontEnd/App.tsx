import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Image,
  KeyboardAvoidingView,
  Linking,
  Modal,
  PermissionsAndroid,
  Platform,
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
} from 'react-native';
import '@react-native-firebase/app';
import {
  getAuth,
  signInWithPhoneNumber,
  signOut,
  ConfirmationResult,
} from '@react-native-firebase/auth';
import {
  getMessaging,
  getToken,
  onMessage,
  onTokenRefresh,
  onNotificationOpenedApp,
  requestPermission,
  RemoteMessage,
} from '@react-native-firebase/messaging';
import { launchCamera } from 'react-native-image-picker';
import Geolocation from '@react-native-community/geolocation';

import { authenticateWithBackend, BackendAuthResponse } from './src/services/authService';
import { API_BASE_URL } from './src/config/api';
import {
  createComplaint,
  getMyComplaints,
  uploadImage,
  registerFcmToken,
  getWorkerComplaints,
  updateWorkerStatus,
  uploadWorkerProofImage,
  checkDuplicateComplaints,
  joinComplaint,
  getJoinedComplaints,
  ComplaintData,
  CreateComplaintPayload,
  DuplicateComplaintSummary,
  JoinComplaintResponse,
} from './src/services/complaintService';

type AuthStep = 'PHONE' | 'OTP' | 'SUCCESS';
type CitizenTab = 'REPORT' | 'MY_COMPLAINTS';
type WorkerTab = 'ASSIGNED_TASKS' | 'REPORT';

export default function App() {
  const [step, setStep] = useState<AuthStep>('PHONE');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [otpCode, setOtpCode] = useState('');
  const [confirmation, setConfirmation] = useState<ConfirmationResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [backendUser, setBackendUser] = useState<BackendAuthResponse | null>(null);
  const [firebaseIdToken, setFirebaseIdToken] = useState<string | null>(null);

  // Worker Dashboard State
  const [workerComplaints, setWorkerComplaints] = useState<ComplaintData[]>([]);
  const [loadingWorkerComplaints, setLoadingWorkerComplaints] = useState(false);
  const [workerActionLoading, setWorkerActionLoading] = useState<{ [id: number]: boolean }>({});
  const [workerProofPhotos, setWorkerProofPhotos] = useState<{ [id: number]: string }>({});
  const [workerRemarks, setWorkerRemarks] = useState<{ [id: number]: string }>({});
  const [workerSuccessMessage, setWorkerSuccessMessage] = useState<string | null>(null);
  const [workerActiveTab, setWorkerActiveTab] = useState<WorkerTab>('ASSIGNED_TASKS');

  // Citizen Dashboard State
  const [activeTab, setActiveTab] = useState<CitizenTab>('REPORT');
  const [capturedPhotoUri, setCapturedPhotoUri] = useState<string | null>(null);
  const [photoConfirmed, setPhotoConfirmed] = useState(false);
  const [gpsLocation, setGpsLocation] = useState<{ lat: number; lng: number } | null>(null);
  const [gpsLoading, setGpsLoading] = useState(false);
  const [gpsError, setGpsError] = useState<string | null>(null);
  const [issueType, setIssueType] = useState<string>('POTHOLE');
  const [submitting, setSubmitting] = useState(false);
  const [lastSubmittedComplaint, setLastSubmittedComplaint] = useState<ComplaintData | null>(null);

  // Duplicate Detection & Join State
  const [checkingDuplicates, setCheckingDuplicates] = useState(false);
  const [similarComplaints, setSimilarComplaints] = useState<DuplicateComplaintSummary[]>([]);
  const [pendingPayload, setPendingPayload] = useState<CreateComplaintPayload | null>(null);
  const [viewingSimilarDetailsId, setViewingSimilarDetailsId] = useState<number | null>(null);
  const [joiningComplaintId, setJoiningComplaintId] = useState<number | null>(null);
  const [confirmJoinItem, setConfirmJoinItem] = useState<DuplicateComplaintSummary | null>(null);
  const [joinedSuccessComplaint, setJoinedSuccessComplaint] = useState<JoinComplaintResponse | null>(null);
  const [complaintFilter, setComplaintFilter] = useState<'ALL' | 'MINE' | 'JOINED'>('ALL');
  const [joinedComplaintsList, setJoinedComplaintsList] = useState<ComplaintData[]>([]);

  // My Complaints State
  const [complaintsList, setComplaintsList] = useState<ComplaintData[]>([]);
  const [loadingComplaints, setLoadingComplaints] = useState(false);
  const [expandedHistoryId, setExpandedHistoryId] = useState<number | null>(null);

  useEffect(() => {
    if (Platform.OS === 'android' && typeof (StatusBar as any).setBackgroundColor === 'function') {
      (StatusBar as any).setBackgroundColor('#0B1120');
    }
  }, []);

  // Setup FCM push notifications when user is logged in
  useEffect(() => {
    if (!firebaseIdToken) return;

    let unsubscribeOnMessage: (() => void) | undefined;
    let unsubscribeOnTokenRefresh: (() => void) | undefined;

    const setupFcm = async () => {
      try {
        const msg = getMessaging();

        // 1. Request Android 13+ notification permission if applicable
        if (Platform.OS === 'android' && Platform.Version >= 33) {
          const granted = await PermissionsAndroid.request(
            'android.permission.POST_NOTIFICATIONS' as any,
          );
          if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
            console.warn('[FCM] Notification permission was denied by user.');
          }
        } else {
          await requestPermission(msg);
        }

        // 2. Retrieve device registration token
        const token = await getToken(msg);
        if (token) {
          console.log('[FCM] Device Token obtained:', token.substring(0, 15) + '...');
          await registerFcmToken(firebaseIdToken, token);
        }

        // 3. Handle token refresh
        unsubscribeOnTokenRefresh = onTokenRefresh(msg, async (newToken: string) => {
          console.log('[FCM] Token refreshed, re-registering with backend');
          await registerFcmToken(firebaseIdToken, newToken);
        });

        // 4. Handle foreground notifications
        unsubscribeOnMessage = onMessage(msg, async (remoteMessage: RemoteMessage) => {
          console.log('[FCM] Foreground notification received:', remoteMessage);
          const title = remoteMessage.notification?.title || 'UrbanPulse Update';
          const body = remoteMessage.notification?.body || 'Your complaint status has changed.';
          Alert.alert(title, body);
          // Refresh complaints list
          fetchCitizenComplaints(firebaseIdToken);
        });

        // 5. Handle notification opened from background state
        onNotificationOpenedApp(msg, (remoteMessage: RemoteMessage) => {
          console.log('[FCM] Notification opened app from background:', remoteMessage);
          setActiveTab('MY_COMPLAINTS');
          fetchCitizenComplaints(firebaseIdToken);
        });
      } catch (err: any) {
        console.warn('[FCM] Notification setup completed with notice:', err?.message);
      }
    };

    setupFcm();

    return () => {
      if (unsubscribeOnMessage) unsubscribeOnMessage();
      if (unsubscribeOnTokenRefresh) unsubscribeOnTokenRefresh();
    };
  }, [firebaseIdToken]);

  const sanitizePhoneNumber = (raw: string): string => {
    let cleaned = raw.replace(/[\s\-()]/g, '');
    if (cleaned.startsWith('+91')) {
      cleaned = cleaned.substring(3);
    } else if (cleaned.startsWith('91') && cleaned.length === 12) {
      cleaned = cleaned.substring(2);
    } else if (cleaned.startsWith('0') && cleaned.length === 11) {
      cleaned = cleaned.substring(1);
    }
    return cleaned;
  };

  const validatePhoneNumber = (num: string): { error: string | null; cleanNumber: string } => {
    const cleaned = sanitizePhoneNumber(num);
    if (!cleaned) {
      return { error: 'Please enter your mobile number.', cleanNumber: '' };
    }
    if (!/^\d+$/.test(cleaned)) {
      return { error: 'Mobile number must contain only digits.', cleanNumber: '' };
    }
    if (cleaned.length !== 10) {
      return { error: 'Mobile number must be exactly 10 digits.', cleanNumber: '' };
    }
    if (!/^[6-9]/.test(cleaned)) {
      return {
        error: 'Please enter a valid Indian mobile number starting with 6, 7, 8, or 9.',
        cleanNumber: '',
      };
    }
    return { error: null, cleanNumber: cleaned };
  };

  const handleSendOtp = async () => {
    const { error, cleanNumber } = validatePhoneNumber(phoneNumber);
    if (error) {
      setErrorMessage(error);
      return;
    }

    setLoading(true);
    setErrorMessage(null);
    const formattedNumber = `+91${cleanNumber}`;

    try {
      const auth = getAuth();
      const confirmResult = await signInWithPhoneNumber(auth, formattedNumber);
      setConfirmation(confirmResult);
      setStep('OTP');
    } catch (err: any) {
      setErrorMessage(err?.message || 'Failed to send OTP. Please check mobile number.');
    } finally {
      setLoading(false);
    }
  };

  const handleVerifyOtp = async () => {
    const trimmedOtp = otpCode.trim();
    if (!trimmedOtp || trimmedOtp.length !== 6 || !/^\d{6}$/.test(trimmedOtp)) {
      setErrorMessage('Please enter a valid 6-digit OTP code.');
      return;
    }

    if (!confirmation) {
      setErrorMessage('Session expired. Please request a new OTP.');
      setStep('PHONE');
      return;
    }

    setLoading(true);
    setErrorMessage(null);

    try {
      const userCredential = await confirmation.confirm(trimmedOtp);
      const auth = getAuth();
      const currentUser = userCredential?.user || auth.currentUser;

      if (!currentUser) {
        throw new Error('Firebase authentication failed.');
      }

      const idToken = await currentUser.getIdToken();
      setFirebaseIdToken(idToken);

      const response = await authenticateWithBackend(idToken);
      if (response && response.authenticated) {
        setBackendUser(response);
        setStep('SUCCESS');
        if (response.role === 'WORKER') {
          fetchWorkerAssignedComplaints(idToken);
        } else {
          fetchCitizenComplaints(idToken);
        }
      } else {
        throw new Error(response?.message || 'Backend authentication failed.');
      }
    } catch (err: any) {
      setErrorMessage(err?.message || 'Invalid OTP code. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  const handleLogout = async () => {
    try {
      const auth = getAuth();
      await signOut(auth);
    } catch {
      // ignore
    }
    setBackendUser(null);
    setFirebaseIdToken(null);
    setConfirmation(null);
    setPhoneNumber('');
    setOtpCode('');
    setCapturedPhotoUri(null);
    setPhotoConfirmed(false);
    setGpsLocation(null);
    setErrorMessage(null);
    setWorkerComplaints([]);
    setWorkerProofPhotos({});
    setWorkerRemarks({});
    setWorkerSuccessMessage(null);
    setWorkerActionLoading({});
    setSimilarComplaints([]);
    setPendingPayload(null);
    setViewingSimilarDetailsId(null);
    setJoiningComplaintId(null);
    setConfirmJoinItem(null);
    setJoinedSuccessComplaint(null);
    setJoinedComplaintsList([]);
    setComplaintFilter('ALL');
    setStep('PHONE');
  };

  const fetchWorkerAssignedComplaints = async (token?: string) => {
    const activeToken = token || firebaseIdToken;
    if (!activeToken) return;

    setLoadingWorkerComplaints(true);
    setErrorMessage(null);
    try {
      const list = await getWorkerComplaints(activeToken);
      setWorkerComplaints(list);
    } catch (err: any) {
      console.warn('Failed to load worker tasks:', err?.message);
      setErrorMessage(err?.message || 'Failed to load assigned field tasks.');
    } finally {
      setLoadingWorkerComplaints(false);
    }
  };

  /**
   * Worker Live Camera Launch
   * Strictly opens the rear camera directly.
   * Prohibits gallery access, document picking, or file selection.
   */
  const handleLaunchWorkerLiveCamera = async (complaintId: number) => {
    setErrorMessage(null);
    setWorkerSuccessMessage(null);

    // 1. Request Android Camera Permission
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Live Camera Permission',
            message: 'UrbanPulse requires access to your live camera to take resolution proof photos.',
            buttonPositive: 'Allow Camera',
            buttonNegative: 'Deny',
          },
        );

        if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
          setErrorMessage('Camera permission was denied. Live camera access is required to capture resolution proof.');
          return;
        }
      } catch (err) {
        setErrorMessage('Failed to request camera permission. Please check device settings.');
        return;
      }
    }

    // 2. Open live rear camera directly (same configuration as citizen camera)
    launchCamera(
      {
        mediaType: 'photo',
        cameraType: 'back',
        saveToPhotos: false,
        quality: 0.8,
        maxWidth: 1920,
        maxHeight: 1920,
      },
      (response) => {
        if (response.didCancel) {
          return;
        }
        if (response.errorCode) {
          setErrorMessage(`Camera error: ${response.errorMessage || response.errorCode}`);
          return;
        }
        if (response.assets && response.assets.length > 0) {
          const asset = response.assets[0];
          if (asset.uri) {
            setWorkerProofPhotos((prev) => ({
              ...prev,
              [complaintId]: asset.uri!,
            }));
            setWorkerSuccessMessage('Live proof photo captured! Add remarks if needed and submit.');
          }
        }
      },
    );
  };

  /**
   * Worker starts work on assigned complaint (ASSIGNED -> IN_PROGRESS)
   */
  const handleWorkerStartWork = async (complaintId: number) => {
    if (!firebaseIdToken) return;
    setWorkerActionLoading((prev) => ({ ...prev, [complaintId]: true }));
    setErrorMessage(null);
    setWorkerSuccessMessage(null);
    try {
      const updated = await updateWorkerStatus(
        firebaseIdToken,
        complaintId,
        'IN_PROGRESS',
        'Worker arrived on site and started repair',
      );
      setWorkerSuccessMessage(`Work started on complaint ${updated.complaintNumber}! Status: IN_PROGRESS.`);
      fetchWorkerAssignedComplaints();
    } catch (err: any) {
      setErrorMessage(err?.message || 'Failed to start work on complaint.');
    } finally {
      setWorkerActionLoading((prev) => ({ ...prev, [complaintId]: false }));
    }
  };

  /**
   * Worker submits live proof photo & marks complaint as RESOLVED
   */
  const handleWorkerSubmitProofAndResolve = async (complaintId: number) => {
    const photoUri = workerProofPhotos[complaintId];
    if (!photoUri) {
      setErrorMessage('Please take a resolution proof photo using the live camera before submitting.');
      return;
    }
    if (!firebaseIdToken) {
      setErrorMessage('Authentication session expired. Please log in again.');
      return;
    }

    setWorkerActionLoading((prev) => ({ ...prev, [complaintId]: true }));
    setErrorMessage(null);
    setWorkerSuccessMessage(null);

    try {
      // Step 1: Upload resolution proof photo (multipart POST /api/worker/complaints/{id}/proof)
      console.log(`[WORKER] Uploading live proof photo for complaint ${complaintId}...`);
      await uploadWorkerProofImage(firebaseIdToken, complaintId, photoUri);
      console.log(`[WORKER] Proof photo uploaded successfully.`);

      // Step 2: Mark complaint as RESOLVED (PATCH /api/worker/complaints/{id}/status)
      const remarks = workerRemarks[complaintId]?.trim() || 'Issue successfully fixed and verified on site.';
      console.log(`[WORKER] Updating complaint ${complaintId} status to RESOLVED...`);
      const updated = await updateWorkerStatus(firebaseIdToken, complaintId, 'RESOLVED', remarks);

      const slaMsg = updated.resolvedWithinSla ? 'Resolved within SLA! ✅' : 'Resolved after SLA deadline ⚠️';
      setWorkerSuccessMessage(`Complaint ${updated.complaintNumber} successfully RESOLVED! ${slaMsg}`);

      // Clear cached proof photo & remarks for this complaint
      setWorkerProofPhotos((prev) => {
        const next = { ...prev };
        delete next[complaintId];
        return next;
      });
      setWorkerRemarks((prev) => {
        const next = { ...prev };
        delete next[complaintId];
        return next;
      });

      fetchWorkerAssignedComplaints();
    } catch (err: any) {
      console.error('[WORKER RESOLVE ERROR]', err);
      setErrorMessage(err?.message || 'Failed to submit proof photo and resolve complaint.');
    } finally {
      setWorkerActionLoading((prev) => ({ ...prev, [complaintId]: false }));
    }
  };

  const fetchCitizenComplaints = async (token?: string) => {
    const activeToken = token || firebaseIdToken;
    if (!activeToken) return;

    setLoadingComplaints(true);
    try {
      const [list, joined] = await Promise.all([
        getMyComplaints(activeToken),
        getJoinedComplaints(activeToken).catch(() => []),
      ]);
      setComplaintsList(list);
      setJoinedComplaintsList(joined);
    } catch (err: any) {
      console.warn('Failed to load complaints:', err?.message);
    } finally {
      setLoadingComplaints(false);
    }
  };

  /**
   * Opens live rear camera directly.
   * Prohibits gallery access, sample photos, or preset image chips.
   */
  const handleLaunchLiveCamera = async () => {
    setErrorMessage(null);

    // Request CAMERA permission
    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.request(
          PermissionsAndroid.PERMISSIONS.CAMERA,
          {
            title: 'Camera Permission',
            message: 'UrbanPulse requires access to your live camera to take civic issue photos.',
            buttonPositive: 'Allow',
            buttonNegative: 'Deny',
          },
        );

        if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
          setErrorMessage('Camera permission was denied. Camera is required to report civic issues.');
          return;
        }
      } catch (err) {
        setErrorMessage('Failed to request camera permission.');
        return;
      }
    }

    // Launch rear camera directly
    launchCamera(
      {
        mediaType: 'photo',
        cameraType: 'back',
        saveToPhotos: false,
        quality: 0.8,
        maxWidth: 1920,
        maxHeight: 1920,
      },
      (response) => {
        if (response.didCancel) {
          return;
        }
        if (response.errorCode) {
          setErrorMessage(`Camera error: ${response.errorMessage || response.errorCode}`);
          return;
        }
        if (response.assets && response.assets.length > 0) {
          const asset = response.assets[0];
          if (asset.uri) {
            setCapturedPhotoUri(asset.uri);
            setPhotoConfirmed(false);
          }
        }
      },
    );
  };

  /**
   * Confirms photo preview and requests real device GPS.
   */
  const handleConfirmPhoto = () => {
    setPhotoConfirmed(true);
    fetchRealDeviceGps();
  };

  const handleRetakePhoto = () => {
    setCapturedPhotoUri(null);
    setPhotoConfirmed(false);
    setGpsLocation(null);
    setGpsError(null);
    handleLaunchLiveCamera();
  };

  /**
   * Queries real device GPS module.
   * Real coordinates only; no Bangalore mock coordinates or fake location chips.
   */
  const fetchRealDeviceGps = async () => {
    setGpsLoading(true);
    setGpsError(null);

    if (Platform.OS === 'android') {
      try {
        const granted = await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
        ]);

        const fineGranted =
          granted[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] ===
          PermissionsAndroid.RESULTS.GRANTED;
        const coarseGranted =
          granted[PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION] ===
          PermissionsAndroid.RESULTS.GRANTED;

        if (!fineGranted && !coarseGranted) {
          setGpsLoading(false);
          setGpsLocation(null);
          setGpsError('Location permission denied. Real GPS is required to tag civic issues.');
          return;
        }
      } catch (err) {
        setGpsLoading(false);
        setGpsLocation(null);
        setGpsError('Failed to request location permission.');
        return;
      }
    }

    const handleGpsSuccess = (position: any) => {
      if (!position || !position.coords) {
        setGpsLocation(null);
        setGpsLoading(false);
        setGpsError('Unable to get valid coordinates from device GPS.');
        return;
      }
      const { latitude, longitude } = position.coords;
      if (
        typeof latitude === 'number' &&
        !isNaN(latitude) &&
        typeof longitude === 'number' &&
        !isNaN(longitude) &&
        latitude >= -90 &&
        latitude <= 90 &&
        longitude >= -180 &&
        longitude <= 180
      ) {
        setGpsLocation({
          lat: Number(latitude.toFixed(6)),
          lng: Number(longitude.toFixed(6)),
        });
        setGpsError(null);
      } else {
        setGpsLocation(null);
        setGpsError('Received invalid coordinate values from GPS.');
      }
      setGpsLoading(false);
    };

    const handleGpsFailure = (error: any) => {
      // Fallback try with enableHighAccuracy: false
      Geolocation.getCurrentPosition(
        (fallbackPos) => {
          handleGpsSuccess(fallbackPos);
        },
        (finalError) => {
          setGpsLocation(null);
          setGpsLoading(false);
          let msg = 'Unable to get GPS location.';
          if (finalError.code === 1) {
            msg = 'Location permission was denied. Please enable location permissions in App Settings.';
          } else if (finalError.code === 2) {
            msg = 'Location provider unavailable. Please turn on device GPS / Location in Android settings.';
          } else if (finalError.code === 3) {
            msg = 'GPS acquisition timed out. Please ensure GPS is enabled and tap Retry.';
          } else if (finalError.message) {
            msg = `Unable to get GPS location: ${finalError.message}`;
          }
          setGpsError(msg);
        },
        {
          enableHighAccuracy: false,
          timeout: 10000,
          maximumAge: 10000,
        },
      );
    };

    Geolocation.getCurrentPosition(
      handleGpsSuccess,
      handleGpsFailure,
      {
        enableHighAccuracy: true,
        timeout: 15000,
        maximumAge: 10000,
      },
    );
  };

  /**
   * Helper variables for submit state
   */
  const hasValidCoords = Boolean(
    gpsLocation &&
    typeof gpsLocation.lat === 'number' &&
    typeof gpsLocation.lng === 'number' &&
    !isNaN(gpsLocation.lat) &&
    !isNaN(gpsLocation.lng) &&
    gpsLocation.lat >= -90 &&
    gpsLocation.lat <= 90 &&
    gpsLocation.lng >= -180 &&
    gpsLocation.lng <= 180
  );

  const isSubmitReady = Boolean(
    issueType &&
    capturedPhotoUri &&
    hasValidCoords &&
    !submitting
  );

  const getSubmitDisabledReason = (): string | null => {
    if (submitting) return null;
    if (!issueType) return 'Please select an issue category.';
    if (!capturedPhotoUri) return 'Capture a photo and get your GPS location before submitting.';
    if (!hasValidCoords) {
      if (gpsLoading) return 'Getting your real GPS location...';
      return 'Real GPS location is required before submitting. Please acquire GPS above.';
    }
    return null;
  };

  /**
   * Submits complaint with live photo + real GPS only.
   * NO description field is included.
   */
  const handleSubmitComplaint = async () => {
    if (!firebaseIdToken) {
      setErrorMessage('Authentication session expired. Please re-login.');
      return;
    }
    if (!issueType) {
      setErrorMessage('Please select an issue category.');
      return;
    }
    if (!capturedPhotoUri) {
      setErrorMessage('Please capture a live photo of the civic issue first.');
      return;
    }
    if (!hasValidCoords || !gpsLocation) {
      setErrorMessage('Real GPS coordinates are required before submission.');
      return;
    }
    if (submitting) {
      return;
    }

    setSubmitting(true);
    setErrorMessage(null);

    try {
      console.log('[COMPLAINT] Starting submission');
      console.log('[COMPLAINT] Uploading image...');
      const uploadedImageUrl = await uploadImage(firebaseIdToken, capturedPhotoUri);
      console.log('[COMPLAINT] Image upload successful. Path:', uploadedImageUrl);

      // Step 2: Submit complaint to /api/complaints (no description field)
      const payload: CreateComplaintPayload = {
        imageUrl: uploadedImageUrl,
        latitude: gpsLocation.lat,
        longitude: gpsLocation.lng,
        issueType: issueType,
        severity: 'MEDIUM',
      };

      // Step 2: Check for potential duplicate complaints within 30 meters
      console.log('[COMPLAINT] Checking for duplicate complaints within 30m radius...');
      setCheckingDuplicates(true);

      const duplicateCheck = await checkDuplicateComplaints(firebaseIdToken, {
        issueType: payload.issueType || 'POTHOLE',
        latitude: payload.latitude,
        longitude: payload.longitude,
        imageUrl: payload.imageUrl,
        radiusMeters: 30.0,
      });

      setCheckingDuplicates(false);

      if (duplicateCheck.duplicateFound && duplicateCheck.matches && duplicateCheck.matches.length > 0) {
        console.log(`[COMPLAINT] Found ${duplicateCheck.matches.length} matching complaints nearby.`);
        setPendingPayload(payload);
        setSimilarComplaints(duplicateCheck.matches);
        return;
      }

      // Step 3: No duplicate found - proceed directly to normal creation
      console.log('[COMPLAINT] No duplicate found. Creating complaint with safe payload:', {
        issueType: payload.issueType,
        latitude: payload.latitude,
        longitude: payload.longitude,
        imageUrl: payload.imageUrl,
      });
      const result = await createComplaint(firebaseIdToken, payload);
      console.log('[COMPLAINT] Complaint creation successful. Number:', result.complaintNumber);

      setLastSubmittedComplaint(result);
      setCapturedPhotoUri(null);
      setPhotoConfirmed(false);
      setGpsLocation(null);
      setGpsError(null);
      setPendingPayload(null);
      setSimilarComplaints([]);
      fetchCitizenComplaints(firebaseIdToken);
    } catch (err: any) {
      console.error('[COMPLAINT SUBMISSION] Error:', err);
      let userFriendlyError = 'Unable to submit complaint. Please check your connection and try again.';
      if (
        err?.message?.includes('Upload') ||
        err?.message?.includes('upload') ||
        err?.message?.includes('Image') ||
        err?.message?.includes('image') ||
        err?.message?.includes('large')
      ) {
        userFriendlyError = err.message || 'Unable to upload photo. Please try again.';
      } else if (err?.message) {
        userFriendlyError = err.message;
      }
      setErrorMessage(userFriendlyError);
    } finally {
      setSubmitting(false);
      setCheckingDuplicates(false);
    }
  };

  /**
   * Called when citizen confirms that existing similar complaints do NOT describe their issue,
   * so they register a separate complaint.
   */
  const handleProceedWithNewComplaint = async () => {
    if (!firebaseIdToken || !pendingPayload) {
      setErrorMessage('Missing complaint data. Please retry report.');
      return;
    }

    setSubmitting(true);
    setErrorMessage(null);

    try {
      console.log('[COMPLAINT] Citizen confirmed separate issue. Creating new complaint...');
      const result = await createComplaint(firebaseIdToken, pendingPayload);
      setLastSubmittedComplaint(result);
      setCapturedPhotoUri(null);
      setPhotoConfirmed(false);
      setGpsLocation(null);
      setGpsError(null);
      setPendingPayload(null);
      setSimilarComplaints([]);
      fetchCitizenComplaints(firebaseIdToken);
    } catch (err: any) {
      console.error('[COMPLAINT] Separate creation failed:', err);
      setErrorMessage(err?.message || 'Failed to register separate complaint.');
    } finally {
      setSubmitting(false);
    }
  };

  /**
   * Called when citizen confirms joining an existing complaint.
   * Links citizen in complaint_participants without creating duplicate complaint or assignment.
   */
  const handleConfirmJoin = async (complaintId: number) => {
    if (!firebaseIdToken) {
      setErrorMessage('Authentication session expired. Please re-login.');
      return;
    }

    setJoiningComplaintId(complaintId);
    setErrorMessage(null);

    try {
      console.log(`[COMPLAINT] Citizen joining existing complaint ID: ${complaintId}`);
      const result = await joinComplaint(firebaseIdToken, complaintId);
      setJoinedSuccessComplaint(result);
      setConfirmJoinItem(null);
      setSimilarComplaints([]);
      setPendingPayload(null);
      setCapturedPhotoUri(null);
      setPhotoConfirmed(false);
      setGpsLocation(null);
      setGpsError(null);
      fetchCitizenComplaints(firebaseIdToken);
    } catch (err: any) {
      console.error('[COMPLAINT] Join failed:', err);
      setErrorMessage(err?.message || 'Failed to join complaint.');
    } finally {
      setJoiningComplaintId(null);
    }
  };

  const getSlaBadgeText = (complaint: ComplaintData) => {
    if (complaint.status === 'RESOLVED') {
      return { text: 'RESOLVED', color: '#34D399', bg: '#065F46' };
    }
    if (complaint.slaStatus === 'OVERDUE') {
      return { text: 'OVERDUE', color: '#F87171', bg: '#7F1D1D' };
    }
    if (complaint.slaStatus === 'DUE_SOON') {
      const mins = Math.max(0, complaint.remainingMinutes || 0);
      const hours = Math.floor(mins / 60);
      return { text: `DUE SOON: ${hours}h ${mins % 60}m`, color: '#FBBF24', bg: '#78350F' };
    }
    const mins = Math.max(0, complaint.remainingMinutes || 0);
    const hours = Math.floor(mins / 60);
    return { text: `ON TIME: ${hours}h ${mins % 60}m`, color: '#6EE7B7', bg: '#064E3B' };
  };

  const getStatusBadgeStyle = (status: string) => {
    switch (status) {
      case 'SUBMITTED':
        return { bg: '#78350F33', border: '#F59E0B66', text: '#F59E0B' };
      case 'FORWARDED':
        return { bg: '#1E3A8A33', border: '#3B82F666', text: '#60A5FA' };
      case 'ASSIGNED':
        return { bg: '#581C8733', border: '#A855F766', text: '#C084FC' };
      case 'IN_PROGRESS':
        return { bg: '#701A7533', border: '#E879F966', text: '#F0ABFC' };
      case 'RESOLVED':
        return { bg: '#065F4633', border: '#10B98166', text: '#34D399' };
      default:
        return { bg: '#33415533', border: '#64748B66', text: '#94A3B8' };
    }
  };

  const renderReportForm = () => {
    if (joinedSuccessComplaint) {
      return (
        <View style={styles.successCard}>
          <Text style={styles.successCheckIcon}>🤝</Text>
          <Text style={styles.successCardTitle}>Joined Complaint Successfully!</Text>
          <Text style={styles.complaintNumBox}>
            Complaint #: {joinedSuccessComplaint.complaintNumber}
          </Text>
          <Text style={styles.successDesc}>
            {joinedSuccessComplaint.message || 'You have been linked to this existing civic complaint.'}
          </Text>

          <View style={styles.joinedStatsBox}>
            <Text style={styles.joinedStatsNumber}>👥 {joinedSuccessComplaint.joinCount}</Text>
            <Text style={styles.joinedStatsLabel}>Total Citizens Participating</Text>
          </View>

          <TouchableOpacity
            style={styles.primaryButton}
            onPress={() => {
              setJoinedSuccessComplaint(null);
              setActiveTab('MY_COMPLAINTS');
              fetchCitizenComplaints();
            }}>
            <Text style={styles.primaryButtonText}>View in My Complaints</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.secondaryButton, { marginTop: 10 }]}
            onPress={() => {
              setJoinedSuccessComplaint(null);
              setActiveTab('REPORT');
            }}>
            <Text style={styles.secondaryButtonText}>Report Another Issue</Text>
          </TouchableOpacity>
        </View>
      );
    }

    if (similarComplaints.length > 0) {
      return (
        <View style={styles.similarContainer}>
          <View style={styles.similarHeaderBanner}>
            <Text style={styles.similarBannerIcon}>🔍</Text>
            <View style={{ flex: 1 }}>
              <Text style={styles.similarBannerTitle}>Similar Complaints Found</Text>
              <Text style={styles.similarBannerSub}>
                We detected {similarComplaints.length} existing civic complaint{similarComplaints.length > 1 ? 's' : ''} reported within 30m matching this issue category ({issueType}).
              </Text>
            </View>
          </View>

          <Text style={styles.similarInstructionText}>
            Inspect the reports below. If your issue is already listed, tap <Text style={{ fontWeight: '700', color: '#38BDF8' }}>Join Complaint</Text> to follow its resolution without filing a duplicate ticket.
          </Text>

          {similarComplaints.map((item) => {
            const badgeStyle = getStatusBadgeStyle(item.status);
            const isInspecting = viewingSimilarDetailsId === item.id;
            const isJoining = joiningComplaintId === item.id;

            return (
              <View key={item.id} style={styles.similarCard}>
                <View style={styles.similarCardHeader}>
                  <View style={{ flex: 1 }}>
                    <Text style={styles.similarComplaintNumber}>{item.complaintNumber}</Text>
                    <Text style={styles.similarDistanceBadge}>
                      📍 {item.distanceMeters.toFixed(1)}m away from your location
                    </Text>
                  </View>
                  <View style={[styles.statusBadge, { backgroundColor: badgeStyle.bg, borderColor: badgeStyle.border }]}>
                    <Text style={[styles.statusBadgeText, { color: badgeStyle.text }]}>{item.status}</Text>
                  </View>
                </View>

                {item.imageUrl ? (
                  <View style={styles.similarImageContainer}>
                    <Image
                      source={{
                        uri: item.imageUrl.startsWith('/') ? `${API_BASE_URL}${item.imageUrl}` : item.imageUrl,
                      }}
                      style={styles.similarImage}
                    />
                  </View>
                ) : null}

                <View style={styles.similarMetaBox}>
                  <View style={styles.similarMetaRow}>
                    <Text style={styles.similarMetaLabel}>Issue Category:</Text>
                    <Text style={{ color: '#38BDF8', fontWeight: '700' }}>{item.issueType}</Text>
                  </View>
                  <View style={styles.similarMetaRow}>
                    <Text style={styles.similarMetaLabel}>Citizens Joined:</Text>
                    <Text style={{ color: '#FBBF24', fontWeight: '800' }}>👥 {item.joinCount || 0} Citizens</Text>
                  </View>
                  <View style={styles.similarMetaRow}>
                    <Text style={styles.similarMetaLabel}>Reported Date:</Text>
                    <Text style={{ color: '#CBD5E1' }}>
                      {new Date(item.createdAt).toLocaleDateString()} {new Date(item.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                    </Text>
                  </View>
                  {item.departmentName ? (
                    <View style={styles.similarMetaRow}>
                      <Text style={styles.similarMetaLabel}>Department:</Text>
                      <Text style={{ color: '#CBD5E1' }}>{item.departmentName}</Text>
                    </View>
                  ) : null}
                </View>

                {item.status === 'RESOLVED' && (
                  <View style={styles.similarResolvedNotice}>
                    <Text style={{ color: '#34D399', fontWeight: '700', fontSize: 13 }}>
                      ℹ️ This complaint is marked RESOLVED.
                    </Text>
                    <Text style={{ color: '#94A3B8', fontSize: 12, marginTop: 2 }}>
                      If the issue has reoccurred or remains broken, you can inspect details or register a separate complaint below.
                    </Text>
                  </View>
                )}

                <View style={styles.similarActionRow}>
                  <TouchableOpacity
                    style={styles.similarDetailsBtn}
                    onPress={() => setViewingSimilarDetailsId(isInspecting ? null : item.id)}>
                    <Text style={styles.similarDetailsBtnText}>
                      {isInspecting ? '▲ Hide Details' : '👁️ View Details'}
                    </Text>
                  </TouchableOpacity>

                  {item.hasJoined ? (
                    <View style={styles.alreadyJoinedBadge}>
                      <Text style={styles.alreadyJoinedText}>
                        {item.isOriginalReporter ? '★ Your Report' : '✓ Already Joined'}
                      </Text>
                    </View>
                  ) : item.status === 'RESOLVED' ? (
                    <View style={[styles.alreadyJoinedBadge, { backgroundColor: 'rgba(16, 185, 129, 0.15)', borderColor: '#10B981' }]}>
                      <Text style={[styles.alreadyJoinedText, { color: '#34D399' }]}>
                        ✓ Previously Resolved
                      </Text>
                    </View>
                  ) : (
                    <TouchableOpacity
                      style={[styles.similarJoinBtn, isJoining && styles.buttonDisabled]}
                      disabled={isJoining}
                      onPress={() => setConfirmJoinItem(item)}>
                      {isJoining ? (
                        <ActivityIndicator size="small" color="#FFFFFF" />
                      ) : (
                        <Text style={styles.similarJoinBtnText}>🤝 Join Complaint</Text>
                      )}
                    </TouchableOpacity>
                  )}
                </View>

                {isInspecting && (
                  <View style={styles.similarExpandedBox}>
                    <Text style={styles.similarExpandedTitle}>Technical & Lifecycle Details</Text>
                    <Text style={styles.similarExpandedText}>📍 Coordinates: Lat {item.latitude}, Lng {item.longitude}</Text>
                    {item.slaDeadline && (
                      <Text style={styles.similarExpandedText}>⏰ SLA Deadline: {new Date(item.slaDeadline).toLocaleString()}</Text>
                    )}
                    {item.proofImageUrl && (
                      <View style={{ marginTop: 8 }}>
                        <Text style={{ color: '#34D399', fontSize: 12, fontWeight: '700', marginBottom: 4 }}>Resolution Proof Photo:</Text>
                        <Image
                          source={{ uri: item.proofImageUrl.startsWith('/') ? `${API_BASE_URL}${item.proofImageUrl}` : item.proofImageUrl }}
                          style={{ height: 140, borderRadius: 6, backgroundColor: '#0F172A' }}
                        />
                      </View>
                    )}
                  </View>
                )}
              </View>
            );
          })}

          <TouchableOpacity
            style={styles.noneOfTheseBtn}
            onPress={handleProceedWithNewComplaint}
            disabled={submitting}>
            {submitting ? (
              <ActivityIndicator color="#FFFFFF" size="small" />
            ) : (
              <Text style={styles.noneOfTheseBtnText}>
                ➕ None of These – Register New Complaint
              </Text>
            )}
          </TouchableOpacity>

          <TouchableOpacity
            style={styles.cancelCheckBtn}
            onPress={() => {
              setSimilarComplaints([]);
              setPendingPayload(null);
            }}>
            <Text style={styles.cancelCheckBtnText}>← Go Back to Edit Report</Text>
          </TouchableOpacity>
        </View>
      );
    }

    if (lastSubmittedComplaint) {
      return (
        <View style={styles.successCard}>
          <Text style={styles.successCheckIcon}>✓</Text>
          <Text style={styles.successCardTitle}>Complaint Submitted Successfully!</Text>
          <Text style={styles.complaintNumBox}>
            Complaint #: {lastSubmittedComplaint.complaintNumber}
          </Text>
          <Text style={styles.successDesc}>
            Your issue has been logged in PostgreSQL with status{' '}
            <Text style={{ color: '#F59E0B', fontWeight: '700' }}>SUBMITTED</Text>. SLA deadline is{' '}
            {lastSubmittedComplaint.slaDeadline ? new Date(lastSubmittedComplaint.slaDeadline).toLocaleString() : 'set'}.
          </Text>

          <TouchableOpacity
            style={styles.primaryButton}
            onPress={() => {
              setLastSubmittedComplaint(null);
              if (backendUser?.role === 'WORKER') {
                setWorkerActiveTab('ASSIGNED_TASKS');
                fetchWorkerAssignedComplaints();
              } else {
                setActiveTab('MY_COMPLAINTS');
                fetchCitizenComplaints();
              }
            }}>
            <Text style={styles.primaryButtonText}>
              {backendUser?.role === 'WORKER' ? 'View My Assigned Tasks' : 'View My Complaints'}
            </Text>
          </TouchableOpacity>
        </View>
      );
    }

    return (
      <View style={styles.formCard}>
        <Text style={styles.cardHeaderTitle}>Report Civic Issue</Text>
        <Text style={styles.cardHeaderSub}>
          Live rear camera photo & real GPS location (No description required)
        </Text>

        {/* Step 1: Issue Category Selection */}
        <Text style={styles.inputLabel}>1. Select Issue Category</Text>
        <View style={styles.typeGrid}>
          {[
            { key: 'POTHOLE', label: '🕳️ Pothole (24h)' },
            { key: 'GARBAGE', label: '🗑️ Garbage (12h)' },
            { key: 'WATER_LEAK', label: '💧 Water Leak (6h)' },
            { key: 'DRAINAGE_ISSUE', label: '🚰 Drainage (12h)' },
            { key: 'STREET_LIGHT_DAMAGE', label: '💡 Street Light (24h)' },
            { key: 'INFRASTRUCTURE_DAMAGE', label: '🏗️ Infrastructure (48h)' },
          ].map((item) => (
            <TouchableOpacity
              key={item.key}
              style={[
                styles.typeChip,
                issueType === item.key && styles.typeChipSelected,
              ]}
              onPress={() => setIssueType(item.key)}>
              <Text
                style={[
                  styles.typeChipText,
                  issueType === item.key && styles.typeChipTextSelected,
                ]}>
                {item.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* Step 2: Live Camera Capture */}
        <Text style={[styles.inputLabel, { marginTop: 18 }]}>2. Live Camera Photo</Text>
        {!capturedPhotoUri ? (
          <TouchableOpacity
            style={styles.cameraTriggerBtn}
            onPress={handleLaunchLiveCamera}>
            <Text style={styles.cameraIcon}>📷</Text>
            <Text style={styles.cameraTriggerText}>OPEN LIVE CAMERA</Text>
            <Text style={styles.cameraSubtext}>Rear camera only • No gallery upload</Text>
          </TouchableOpacity>
        ) : (
          <View style={styles.previewContainer}>
            <Image source={{ uri: capturedPhotoUri }} style={styles.photoPreview} />
            <View style={styles.previewActionsRow}>
              <TouchableOpacity
                style={[styles.previewActionBtn, { backgroundColor: '#334155' }]}
                onPress={handleRetakePhoto}>
                <Text style={styles.previewActionBtnText}>🔄 Retake Photo</Text>
              </TouchableOpacity>
              {!photoConfirmed && (
                <TouchableOpacity
                  style={[styles.previewActionBtn, { backgroundColor: '#10B981' }]}
                  onPress={handleConfirmPhoto}>
                  <Text style={styles.previewActionBtnText}>✓ Use This Photo</Text>
                </TouchableOpacity>
              )}
            </View>
          </View>
        )}

        {/* Step 3: Real Device GPS Location */}
        {capturedPhotoUri && (
          <View style={{ marginTop: 18 }}>
            <Text style={styles.inputLabel}>3. Real GPS Location Coordinates</Text>
            {gpsLoading ? (
              <View style={styles.gpsBoxLoading}>
                <ActivityIndicator size="small" color="#38BDF8" />
                <Text style={styles.gpsWaitingText}>Getting your real GPS location...</Text>
              </View>
            ) : hasValidCoords ? (
              <View style={styles.gpsBoxSuccess}>
                <View style={styles.gpsSuccessHeader}>
                  <Text style={styles.gpsTag}>✓ Verified Real Device GPS</Text>
                  <TouchableOpacity
                    style={styles.gpsRefreshBtn}
                    onPress={fetchRealDeviceGps}
                    disabled={gpsLoading}>
                    <Text style={styles.gpsRefreshText}>🔄 Refresh GPS</Text>
                  </TouchableOpacity>
                </View>

                <View style={styles.gpsCoordsCard}>
                  <View style={styles.gpsCoordRow}>
                    <Text style={styles.gpsCoordLabel}>Latitude:</Text>
                    <Text style={styles.gpsCoordValue}>{gpsLocation!.lat.toFixed(6)}</Text>
                  </View>
                  <View style={styles.gpsCoordRow}>
                    <Text style={styles.gpsCoordLabel}>Longitude:</Text>
                    <Text style={styles.gpsCoordValue}>{gpsLocation!.lng.toFixed(6)}</Text>
                  </View>
                </View>
              </View>
            ) : (
              <View style={styles.gpsBoxError}>
                <Text style={styles.gpsErrorText}>
                  {gpsError || 'Unable to get GPS location.'}
                </Text>
                <TouchableOpacity
                  style={styles.retryGpsBtn}
                  onPress={fetchRealDeviceGps}
                  disabled={gpsLoading}>
                  <Text style={styles.retryGpsBtnText}>Retry GPS</Text>
                </TouchableOpacity>
              </View>
            )}
          </View>
        )}

        {errorMessage && (
          <View style={[styles.errorBox, { marginTop: 16 }]}>
            <Text style={styles.errorText}>{errorMessage}</Text>
          </View>
        )}

        {/* Disabled reason notice when not ready */}
        {!isSubmitReady && !submitting && !checkingDuplicates && (
          <View style={styles.submitNoticeBox}>
            <Text style={styles.submitNoticeText}>
              ℹ️ {getSubmitDisabledReason()}
            </Text>
          </View>
        )}

        {/* Submit Button */}
        <TouchableOpacity
          style={[
            styles.submitButton,
            (!isSubmitReady || submitting || checkingDuplicates) && styles.submitButtonDisabled,
          ]}
          onPress={handleSubmitComplaint}
          disabled={!isSubmitReady || submitting || checkingDuplicates}
          activeOpacity={0.8}>
          {submitting || checkingDuplicates ? (
            <View style={styles.submittingRow}>
              <ActivityIndicator color="#FFFFFF" size="small" />
              <Text style={styles.submitButtonText}>
                {checkingDuplicates ? 'CHECKING SIMILAR COMPLAINTS...' : 'SUBMITTING COMPLAINT...'}
              </Text>
            </View>
          ) : (
            <Text
              style={[
                styles.submitButtonText,
                !isSubmitReady && styles.submitButtonTextDisabled,
              ]}>
              SUBMIT COMPLAINT
            </Text>
          )}
        </TouchableOpacity>
      </View>
    );
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <StatusBar barStyle="light-content" />
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={[
          styles.container,
          step === 'SUCCESS' ? { justifyContent: 'flex-start' } : { justifyContent: 'center' },
        ]}>

        {/* PHONE INPUT SCREEN */}
        {step === 'PHONE' && (
          <View style={styles.card}>
            <View style={styles.headerContainer}>
              <Text style={styles.appName}>UrbanPulse</Text>
              <Text style={styles.appTagline}>Smart Civic Intelligence Platform</Text>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.label}>Citizen Mobile Number</Text>
              <View style={styles.phoneInputRow}>
                <View style={styles.countryCodeBox}>
                  <Text style={styles.countryCodeText}>+91</Text>
                </View>
                <TextInput
                  style={styles.phoneInput}
                  placeholder="10 digit number"
                  placeholderTextColor="#64748B"
                  keyboardType="phone-pad"
                  maxLength={10}
                  value={phoneNumber}
                  onChangeText={(text) => {
                    setPhoneNumber(text);
                    if (errorMessage) setErrorMessage(null);
                  }}
                  editable={!loading}
                />
              </View>
            </View>

            {errorMessage && (
              <View style={styles.errorBox}>
                <Text style={styles.errorText}>{errorMessage}</Text>
              </View>
            )}

            <TouchableOpacity
              style={[styles.primaryButton, loading && styles.buttonDisabled]}
              onPress={handleSendOtp}
              disabled={loading}>
              {loading ? <ActivityIndicator color="#FFFFFF" /> : <Text style={styles.primaryButtonText}>Send OTP</Text>}
            </TouchableOpacity>
          </View>
        )}

        {/* OTP VERIFICATION SCREEN */}
        {step === 'OTP' && (
          <View style={styles.card}>
            <View style={styles.headerContainer}>
              <Text style={styles.appName}>UrbanPulse</Text>
              <Text style={styles.appTagline}>Verify Authentication</Text>
            </View>

            <Text style={styles.otpNotice}>
              OTP sent to +91 {sanitizePhoneNumber(phoneNumber)}
            </Text>

            <View style={styles.formGroup}>
              <Text style={styles.label}>Enter 6-digit OTP</Text>
              <TextInput
                style={styles.otpInput}
                placeholder="------"
                placeholderTextColor="#64748B"
                keyboardType="number-pad"
                maxLength={6}
                value={otpCode}
                onChangeText={(text) => {
                  setOtpCode(text);
                  if (errorMessage) setErrorMessage(null);
                }}
                editable={!loading}
                textAlign="center"
              />
            </View>

            {errorMessage && (
              <View style={styles.errorBox}>
                <Text style={styles.errorText}>{errorMessage}</Text>
              </View>
            )}

            <TouchableOpacity
              style={[styles.primaryButton, loading && styles.buttonDisabled]}
              onPress={handleVerifyOtp}
              disabled={loading}>
              {loading ? <ActivityIndicator color="#FFFFFF" /> : <Text style={styles.primaryButtonText}>Verify OTP</Text>}
            </TouchableOpacity>

            <TouchableOpacity
              style={styles.secondaryButton}
              onPress={() => {
                setOtpCode('');
                setErrorMessage(null);
                setStep('PHONE');
              }}
              disabled={loading}>
              <Text style={styles.secondaryButtonText}>Change Mobile Number</Text>
            </TouchableOpacity>
          </View>
        )}

        {/* FIELD WORKER DASHBOARD SCREEN */}
        {step === 'SUCCESS' && backendUser?.role === 'WORKER' && (
          <View style={styles.fullDashboard}>
            {/* Top Bar Header */}
            <View style={styles.topHeader}>
              <View>
                <Text style={[styles.dashboardTitle, { color: '#10B981' }]}>UrbanPulse Field Worker</Text>
                <Text style={styles.dashboardSubtitle}>
                  {backendUser?.name ? `${backendUser.name} • ` : ''}{backendUser?.mobileNumber || phoneNumber} (Department: {backendUser?.departmentName || 'Field Operations'})
                </Text>
              </View>
              <TouchableOpacity style={styles.headerLogoutBtn} onPress={handleLogout}>
                <Text style={styles.headerLogoutBtnText}>Logout</Text>
              </TouchableOpacity>
            </View>

            {/* Worker Success Banner */}
            {workerSuccessMessage && (
              <View style={styles.workerSuccessBox}>
                <Text style={styles.workerSuccessText}>✓ {workerSuccessMessage}</Text>
                <TouchableOpacity onPress={() => setWorkerSuccessMessage(null)}>
                  <Text style={styles.workerDismissText}>✕</Text>
                </TouchableOpacity>
              </View>
            )}

            {/* Error Banner */}
            {errorMessage && (
              <View style={[styles.errorBox, { marginHorizontal: 16, marginTop: 10 }]}>
                <Text style={styles.errorText}>{errorMessage}</Text>
              </View>
            )}

            {/* Worker Navigation Tabs */}
            <View style={styles.tabsRow}>
              <TouchableOpacity
                style={[styles.tabBtn, workerActiveTab === 'ASSIGNED_TASKS' && styles.tabBtnActiveWorker]}
                onPress={() => {
                  setWorkerActiveTab('ASSIGNED_TASKS');
                  fetchWorkerAssignedComplaints();
                }}>
                <Text style={[styles.tabBtnText, workerActiveTab === 'ASSIGNED_TASKS' && styles.tabBtnTextActiveWorker]}>
                  🛠️ My Tasks ({workerComplaints.length})
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.tabBtn, workerActiveTab === 'REPORT' && styles.tabBtnActiveWorker]}
                onPress={() => setWorkerActiveTab('REPORT')}>
                <Text style={[styles.tabBtnText, workerActiveTab === 'REPORT' && styles.tabBtnTextActiveWorker]}>
                  📸 Report Issue
                </Text>
              </TouchableOpacity>
            </View>

            {/* TAB 1: WORKER ASSIGNED TASKS */}
            {workerActiveTab === 'ASSIGNED_TASKS' && (
              <ScrollView
                style={styles.tabContent}
                contentContainerStyle={styles.tabScrollContent}
                showsVerticalScrollIndicator={true}>
                <View style={styles.listHeaderRow}>
                  <Text style={styles.listHeaderTitle}>My Assigned Field Tasks</Text>
                  <TouchableOpacity
                    style={styles.refreshBtn}
                    onPress={() => fetchWorkerAssignedComplaints()}>
                    <Text style={styles.refreshBtnText}>🔄 Refresh Tasks</Text>
                  </TouchableOpacity>
                </View>

                {loadingWorkerComplaints ? (
                  <View style={{ padding: 40, alignItems: 'center' }}>
                    <ActivityIndicator size="large" color="#10B981" />
                    <Text style={{ color: '#94A3B8', marginTop: 12 }}>Loading assigned field tasks...</Text>
                  </View>
                ) : workerComplaints.length === 0 ? (
                  <View style={styles.emptyCard}>
                    <Text style={styles.emptyIcon}>🛠️</Text>
                    <Text style={styles.emptyTitle}>No Assigned Tasks</Text>
                    <Text style={styles.emptySub}>
                      You currently have no complaints assigned to you. When a supervisor dispatches work, it will appear here.
                    </Text>
                  </View>
                ) : (
                  workerComplaints.map((item) => {
                    const badgeStyle = getStatusBadgeStyle(item.status);
                    const slaBadge = getSlaBadgeText(item);
                    const isExpanded = expandedHistoryId === item.id;
                    const hasPhoto = Boolean(workerProofPhotos[item.id]);
                    const isActionBusy = Boolean(workerActionLoading[item.id]);

                    return (
                      <View key={item.id} style={styles.complaintCard}>
                        {/* Header */}
                        <View style={styles.complaintCardHeader}>
                          <Text style={styles.complaintNumber}>{item.complaintNumber}</Text>
                          <View style={{ flexDirection: 'row', gap: 6 }}>
                            <View
                              style={[
                                styles.statusBadge,
                                { backgroundColor: badgeStyle.bg, borderColor: badgeStyle.border },
                              ]}>
                              <Text style={[styles.statusBadgeText, { color: badgeStyle.text }]}>
                                {item.status}
                              </Text>
                            </View>
                            <View
                              style={[
                                styles.statusBadge,
                                { backgroundColor: slaBadge.bg, borderColor: slaBadge.color },
                              ]}>
                              <Text style={[styles.statusBadgeText, { color: slaBadge.color }]}>
                                {slaBadge.text}
                              </Text>
                            </View>
                          </View>
                        </View>

                        {/* Citizen Original Issue Photo */}
                        {item.imageUrl ? (
                          <View style={styles.cardImageContainer}>
                            <Image
                              source={{
                                uri: item.imageUrl.startsWith('/')
                                  ? `${API_BASE_URL}${item.imageUrl}`
                                  : item.imageUrl,
                              }}
                              style={styles.cardImage}
                            />
                          </View>
                        ) : null}

                        {/* Details */}
                        <View style={styles.cardDetailsBox}>
                          <Text style={styles.detailLine}>
                            <Text style={styles.detailLabel}>Issue: </Text>
                            <Text style={{ color: '#38BDF8', fontWeight: '700' }}>{item.issueType}</Text>
                            {item.slaHours ? ` (SLA: ${item.slaHours}h)` : ''}
                          </Text>
                          <Text style={styles.detailLine}>
                            <Text style={styles.detailLabel}>Department: </Text>
                            {item.departmentName || 'Assigned Department'}
                          </Text>
                          <Text style={styles.detailLine}>
                            <Text style={styles.detailLabel}>Citizens Affected: </Text>
                            <Text style={{ color: '#FBBF24', fontWeight: '800' }}>👥 {item.joinCount || 0} Citizens Joined</Text>
                          </Text>
                          <Text style={styles.detailLine}>
                            <Text style={styles.detailLabel}>Location: </Text>
                            📍 Lat {item.latitude ?? 'N/A'}, Lng {item.longitude ?? 'N/A'}
                          </Text>
                          {item.latitude !== null &&
                          item.latitude !== undefined &&
                          item.longitude !== null &&
                          item.longitude !== undefined &&
                          !isNaN(Number(item.latitude)) &&
                          !isNaN(Number(item.longitude)) &&
                          Number(item.latitude) >= -90 &&
                          Number(item.latitude) <= 90 &&
                          Number(item.longitude) >= -180 &&
                          Number(item.longitude) <= 180 ? (
                            <TouchableOpacity
                              style={styles.workerMapsBtn}
                              onPress={() =>
                                Linking.openURL(
                                  `https://www.google.com/maps?q=${item.latitude},${item.longitude}`,
                                )
                              }>
                              <Text style={styles.workerMapsBtnText}>📍 View on Google Maps</Text>
                            </TouchableOpacity>
                          ) : (
                            <View style={styles.workerLocationUnavailableBox}>
                              <Text style={styles.workerLocationUnavailableText}>Location unavailable</Text>
                            </View>
                          )}
                          <Text style={styles.detailLine}>
                            <Text style={styles.detailLabel}>Submitted: </Text>
                            {new Date(item.createdAt).toLocaleString()}
                          </Text>
                          {item.slaDeadline && (
                            <Text style={styles.detailLine}>
                              <Text style={styles.detailLabel}>SLA Deadline: </Text>
                              {new Date(item.slaDeadline).toLocaleString()}
                            </Text>
                          )}
                          {item.resolvedAt && (
                            <Text style={styles.detailLine}>
                              <Text style={styles.detailLabel}>Resolved At: </Text>
                              <Text style={{ color: item.resolvedWithinSla ? '#34D399' : '#F87171', fontWeight: '700' }}>
                                {new Date(item.resolvedAt).toLocaleString()} ({item.resolvedWithinSla ? 'Within SLA' : 'Overdue'})
                              </Text>
                            </Text>
                          )}
                        </View>

                        {/* WORKER ACTION & PROOF PHOTO FLOW */}
                        <View style={styles.workerActionContainer}>
                          {/* Case 1: ASSIGNED -> START WORK */}
                          {item.status === 'ASSIGNED' && (
                            <TouchableOpacity
                              style={[styles.workerStartWorkBtn, isActionBusy && styles.buttonDisabled]}
                              disabled={isActionBusy}
                              onPress={() => handleWorkerStartWork(item.id)}>
                              {isActionBusy ? (
                                <ActivityIndicator color="#FFFFFF" size="small" />
                              ) : (
                                <Text style={styles.workerStartWorkBtnText}>⚙️ START WORK (Set IN_PROGRESS)</Text>
                              )}
                            </TouchableOpacity>
                          )}

                          {/* Case 2: IN_PROGRESS -> LIVE CAMERA PROOF & RESOLVE */}
                          {item.status === 'IN_PROGRESS' && (
                            <View style={styles.workerProofSection}>
                              <Text style={styles.workerProofSectionTitle}>
                                📸 Resolution Proof (Live Camera Only):
                              </Text>

                              {!hasPhoto ? (
                                /* Live Camera Trigger - strictly NO gallery or file chooser */
                                <TouchableOpacity
                                  style={styles.workerCameraBtn}
                                  onPress={() => handleLaunchWorkerLiveCamera(item.id)}>
                                  <Text style={styles.workerCameraIcon}>📷</Text>
                                  <Text style={styles.workerCameraBtnText}>TAKE PROOF PHOTO</Text>
                                  <Text style={styles.cameraSubtext}>Live rear camera only • No gallery upload</Text>
                                </TouchableOpacity>
                              ) : (
                                /* Photo preview & Retake photo */
                                <View style={styles.workerPreviewContainer}>
                                  <Image
                                    source={{ uri: workerProofPhotos[item.id] }}
                                    style={styles.workerPhotoPreview}
                                  />
                                  <TouchableOpacity
                                    style={styles.workerRetakeBtn}
                                    onPress={() => handleLaunchWorkerLiveCamera(item.id)}>
                                    <Text style={styles.workerRetakeBtnText}>🔄 Retake Photo</Text>
                                  </TouchableOpacity>
                                </View>
                              )}

                              {/* Remarks TextInput */}
                              <TextInput
                                style={styles.workerRemarksInput}
                                placeholder="Remarks (e.g. Repaired pothole with asphalt mix)"
                                placeholderTextColor="#64748B"
                                value={workerRemarks[item.id] || ''}
                                onChangeText={(text) => {
                                  setWorkerRemarks((prev) => ({ ...prev, [item.id]: text }));
                                  if (errorMessage) setErrorMessage(null);
                                }}
                              />

                              {/* Submit Proof & Resolve Button */}
                              <TouchableOpacity
                                style={[
                                  styles.workerSubmitBtn,
                                  (!hasPhoto || isActionBusy) && styles.workerSubmitBtnDisabled,
                                ]}
                                disabled={!hasPhoto || isActionBusy}
                                onPress={() => handleWorkerSubmitProofAndResolve(item.id)}>
                                {isActionBusy ? (
                                  <View style={styles.submittingRow}>
                                    <ActivityIndicator color="#FFFFFF" size="small" />
                                    <Text style={styles.workerSubmitBtnText}>SUBMITTING PROOF & RESOLVING...</Text>
                                  </View>
                                ) : (
                                  <Text
                                    style={[
                                      styles.workerSubmitBtnText,
                                      !hasPhoto && styles.workerSubmitBtnTextDisabled,
                                    ]}>
                                    {hasPhoto ? '✅ SUBMIT PROOF & RESOLVE' : '📷 CAPTURE PHOTO TO RESOLVE'}
                                  </Text>
                                )}
                              </TouchableOpacity>
                            </View>
                          )}

                          {/* Case 3: RESOLVED */}
                          {item.status === 'RESOLVED' && (
                            <View style={styles.resolvedProofBox}>
                              <Text style={styles.resolvedProofTitle}>
                                🎉 Complaint Resolved & Verified {item.resolvedWithinSla ? '(Within SLA)' : '(Overdue)'}
                              </Text>
                              {item.proofImageUrl ? (
                                <Image
                                  source={{
                                    uri: item.proofImageUrl.startsWith('/')
                                      ? `${API_BASE_URL}${item.proofImageUrl}`
                                      : item.proofImageUrl,
                                  }}
                                  style={styles.proofImageThumb}
                                />
                              ) : null}
                            </View>
                          )}
                        </View>

                        {/* Lifecycle Timeline */}
                        <TouchableOpacity
                          style={styles.toggleHistoryBtn}
                          onPress={() => setExpandedHistoryId(isExpanded ? null : item.id)}>
                          <Text style={styles.toggleHistoryText}>
                            {isExpanded ? '▲ Hide Timeline' : `▼ View Lifecycle Timeline (${item.statusHistory?.length || 0})`}
                          </Text>
                        </TouchableOpacity>

                        {isExpanded && item.statusHistory && (
                          <View style={styles.historyContainer}>
                            {item.statusHistory.map((h, idx) => (
                              <View key={h.id || idx} style={styles.historyItem}>
                                <View style={styles.historyDot} />
                                <View style={styles.historyContent}>
                                  <Text style={styles.historyStatusText}>
                                    {h.oldStatus ? `${h.oldStatus} ➔ ${h.newStatus}` : h.newStatus}
                                  </Text>
                                  <Text style={styles.historyMetaText}>
                                    {h.changedByName || h.changedByMobile || 'System'} • {new Date(h.changedAt).toLocaleString()}
                                  </Text>
                                  {h.remarks ? (
                                    <Text style={styles.historyRemarksText}>"{h.remarks}"</Text>
                                  ) : null}
                                </View>
                              </View>
                            ))}
                          </View>
                        )}
                      </View>
                    );
                  })
                )}
              </ScrollView>
            )}

            {/* TAB 2: CITIZEN REPORT FORM FOR WORKER */}
            {workerActiveTab === 'REPORT' && (
              <ScrollView
                style={styles.tabContent}
                contentContainerStyle={styles.tabScrollContent}
                showsVerticalScrollIndicator={true}
                keyboardShouldPersistTaps="handled">
                {renderReportForm()}
              </ScrollView>
            )}
          </View>
        )}

        {/* CITIZEN DASHBOARD SCREEN (when role is NOT WORKER) */}
        {step === 'SUCCESS' && backendUser?.role !== 'WORKER' && (
          <View style={styles.fullDashboard}>
            {/* Top Bar Header */}
            <View style={styles.topHeader}>
              <View>
                <Text style={styles.dashboardTitle}>UrbanPulse Citizen</Text>
                <Text style={styles.dashboardSubtitle}>
                  {backendUser?.mobileNumber || phoneNumber} ({backendUser?.role || 'CITIZEN'})
                </Text>
              </View>
              <TouchableOpacity style={styles.headerLogoutBtn} onPress={handleLogout}>
                <Text style={styles.headerLogoutBtnText}>Logout</Text>
              </TouchableOpacity>
            </View>

            {/* Navigation Tabs */}
            <View style={styles.tabsRow}>
              <TouchableOpacity
                style={[styles.tabBtn, activeTab === 'REPORT' && styles.tabBtnActive]}
                onPress={() => setActiveTab('REPORT')}>
                <Text style={[styles.tabBtnText, activeTab === 'REPORT' && styles.tabBtnTextActive]}>
                  📸 Report Issue
                </Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.tabBtn, activeTab === 'MY_COMPLAINTS' && styles.tabBtnActive]}
                onPress={() => {
                  setActiveTab('MY_COMPLAINTS');
                  fetchCitizenComplaints();
                }}>
                <Text style={[styles.tabBtnText, activeTab === 'MY_COMPLAINTS' && styles.tabBtnTextActive]}>
                  📋 My Complaints ({complaintsList.length + joinedComplaintsList.length})
                </Text>
              </TouchableOpacity>
            </View>

            {/* TAB 1: REPORT ISSUE */}
            {activeTab === 'REPORT' && (
              <ScrollView
                style={styles.tabContent}
                contentContainerStyle={styles.tabScrollContent}
                showsVerticalScrollIndicator={true}
                keyboardShouldPersistTaps="handled">
                {renderReportForm()}
              </ScrollView>
            )}

            {/* TAB 2: MY COMPLAINTS */}
            {activeTab === 'MY_COMPLAINTS' && (
              <ScrollView
                style={styles.tabContent}
                contentContainerStyle={styles.tabScrollContent}
                showsVerticalScrollIndicator={true}>
                <View style={styles.listHeaderRow}>
                  <Text style={styles.listHeaderTitle}>My Complaints & Joined Issues</Text>
                  <TouchableOpacity
                    style={styles.refreshBtn}
                    onPress={() => fetchCitizenComplaints()}>
                    <Text style={styles.refreshBtnText}>🔄 Refresh</Text>
                  </TouchableOpacity>
                </View>

                {/* Filter Segments */}
                <View style={styles.complaintFilterRow}>
                  <TouchableOpacity
                    style={[styles.filterChip, complaintFilter === 'ALL' && styles.filterChipActive]}
                    onPress={() => setComplaintFilter('ALL')}>
                    <Text style={[styles.filterChipText, complaintFilter === 'ALL' && styles.filterChipTextActive]}>
                      All ({complaintsList.length + joinedComplaintsList.length})
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.filterChip, complaintFilter === 'MINE' && styles.filterChipActive]}
                    onPress={() => setComplaintFilter('MINE')}>
                    <Text style={[styles.filterChipText, complaintFilter === 'MINE' && styles.filterChipTextActive]}>
                      Reported by Me ({complaintsList.length})
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[styles.filterChip, complaintFilter === 'JOINED' && styles.filterChipActive]}
                    onPress={() => setComplaintFilter('JOINED')}>
                    <Text style={[styles.filterChipText, complaintFilter === 'JOINED' && styles.filterChipTextActive]}>
                      Joined by Me ({joinedComplaintsList.length})
                    </Text>
                  </TouchableOpacity>
                </View>

                {loadingComplaints ? (
                  <View style={{ padding: 40, alignItems: 'center' }}>
                    <ActivityIndicator size="large" color="#38BDF8" />
                    <Text style={{ color: '#94A3B8', marginTop: 12 }}>Loading complaints...</Text>
                  </View>
                ) : (
                  (() => {
                    const displayedComplaints =
                      complaintFilter === 'MINE'
                        ? complaintsList
                        : complaintFilter === 'JOINED'
                        ? joinedComplaintsList
                        : [...complaintsList, ...joinedComplaintsList.filter((j) => !complaintsList.some((m) => m.id === j.id))];

                    if (displayedComplaints.length === 0) {
                      return (
                        <View style={styles.emptyCard}>
                          <Text style={styles.emptyIcon}>📭</Text>
                          <Text style={styles.emptyTitle}>
                            {complaintFilter === 'JOINED'
                              ? 'No Joined Complaints'
                              : complaintFilter === 'MINE'
                              ? 'No Reported Complaints'
                              : 'No Complaints Found'}
                          </Text>
                          <Text style={styles.emptySub}>
                            {complaintFilter === 'JOINED'
                              ? 'You have not joined any existing civic complaints yet.'
                              : 'You have not submitted any complaints yet. Tap "Report Issue" above to submit one.'}
                          </Text>
                        </View>
                      );
                    }

                    return displayedComplaints.map((item) => {
                      const badgeStyle = getStatusBadgeStyle(item.status);
                      const slaBadge = getSlaBadgeText(item);
                      const isExpanded = expandedHistoryId === item.id;
                      const isJoinedItem = item.hasJoined && !item.isOriginalReporter;

                      return (
                        <View key={item.id} style={styles.complaintCard}>
                          {/* Header */}
                          <View style={styles.complaintCardHeader}>
                            <View>
                              <Text style={styles.complaintNumber}>{item.complaintNumber}</Text>
                              <View style={isJoinedItem ? styles.originBadgeJoined : styles.originBadgeReporter}>
                                <Text style={styles.originBadgeText}>
                                  {isJoinedItem ? '🤝 Joined by You' : '📝 Original Reporter'}
                                </Text>
                              </View>
                            </View>
                            <View style={{ flexDirection: 'row', gap: 6, alignItems: 'center' }}>
                              <View
                                style={[
                                  styles.statusBadge,
                                  { backgroundColor: badgeStyle.bg, borderColor: badgeStyle.border },
                                ]}>
                                <Text style={[styles.statusBadgeText, { color: badgeStyle.text }]}>
                                  {item.status}
                                </Text>
                              </View>
                              <View
                                style={[
                                  styles.statusBadge,
                                  { backgroundColor: slaBadge.bg, borderColor: slaBadge.color },
                                ]}>
                                <Text style={[styles.statusBadgeText, { color: slaBadge.color }]}>
                                  {slaBadge.text}
                                </Text>
                              </View>
                            </View>
                          </View>

                          {/* Complaint Image */}
                          {item.imageUrl ? (
                            <View style={styles.cardImageContainer}>
                              <Image
                                source={{
                                  uri: item.imageUrl.startsWith('/')
                                    ? `${API_BASE_URL}${item.imageUrl}`
                                    : item.imageUrl,
                                }}
                                style={styles.cardImage}
                              />
                            </View>
                          ) : null}

                          {/* Details */}
                          <View style={styles.cardDetailsBox}>
                            <View style={styles.communityImpactBanner}>
                              <Text style={styles.communityImpactText}>
                                👥 <Text style={{ fontWeight: '800', color: '#FBBF24' }}>{item.joinCount || 0} Citizen{item.joinCount === 1 ? '' : 's'}</Text> Participating
                              </Text>
                            </View>

                            <Text style={styles.detailLine}>
                              <Text style={styles.detailLabel}>Issue: </Text>
                              <Text style={{ color: '#38BDF8', fontWeight: '700' }}>{item.issueType}</Text>
                            </Text>
                            <Text style={styles.detailLine}>
                              <Text style={styles.detailLabel}>Department: </Text>
                              {item.departmentName || 'Awaiting Admin Routing'}
                            </Text>
                            <Text style={styles.detailLine}>
                              <Text style={styles.detailLabel}>Location: </Text>
                              📍 Lat {item.latitude}, Lng {item.longitude}
                            </Text>
                            <Text style={styles.detailLine}>
                              <Text style={styles.detailLabel}>Submitted: </Text>
                              {new Date(item.createdAt).toLocaleString()}
                            </Text>
                            {item.slaDeadline && (
                              <Text style={styles.detailLine}>
                                <Text style={styles.detailLabel}>SLA Deadline: </Text>
                                {new Date(item.slaDeadline).toLocaleString()}
                              </Text>
                            )}
                          </View>

                          {/* Resolution Proof Card if Resolved */}
                          {item.status === 'RESOLVED' && (
                            <View style={styles.resolvedProofBox}>
                              <Text style={styles.resolvedProofTitle}>
                                🎉 Issue Resolved {item.resolvedWithinSla ? '(Within SLA)' : '(Overdue)'}
                              </Text>
                              {item.proofImageUrl ? (
                                <Image
                                  source={{
                                    uri: item.proofImageUrl.startsWith('/')
                                      ? `${API_BASE_URL}${item.proofImageUrl}`
                                      : item.proofImageUrl,
                                  }}
                                  style={styles.proofImageThumb}
                                />
                              ) : null}
                            </View>
                          )}

                          {/* Expandable Status History */}
                          <TouchableOpacity
                            style={styles.toggleHistoryBtn}
                            onPress={() => setExpandedHistoryId(isExpanded ? null : item.id)}>
                            <Text style={styles.toggleHistoryText}>
                              {isExpanded ? '▲ Hide Timeline' : `▼ View Lifecycle Timeline (${item.statusHistory?.length || 0})`}
                            </Text>
                          </TouchableOpacity>

                          {isExpanded && item.statusHistory && (
                            <View style={styles.historyContainer}>
                              {item.statusHistory.map((h, idx) => (
                                <View key={h.id || idx} style={styles.historyItem}>
                                  <View style={styles.historyDot} />
                                  <View style={styles.historyContent}>
                                    <Text style={styles.historyStatusText}>
                                      {h.oldStatus ? `${h.oldStatus} ➔ ${h.newStatus}` : h.newStatus}
                                    </Text>
                                    <Text style={styles.historyMetaText}>
                                      {h.changedByName || h.changedByMobile || 'System'} • {new Date(h.changedAt).toLocaleString()}
                                    </Text>
                                    {h.remarks ? (
                                      <Text style={styles.historyRemarksText}>"{h.remarks}"</Text>
                                    ) : null}
                                  </View>
                                </View>
                              ))}
                            </View>
                          )}
                        </View>
                      );
                    });
                  })()
                )}
              </ScrollView>
            )}
          </View>
        )}

        {/* Confirmation Modal for Joining Complaint */}
        {confirmJoinItem && (
          <Modal
            transparent
            animationType="fade"
            visible={Boolean(confirmJoinItem)}
            onRequestClose={() => setConfirmJoinItem(null)}>
            <View style={styles.modalBackdrop}>
              <View style={styles.modalDialogCard}>
                <Text style={styles.modalDialogTitle}>🤝 Join Existing Complaint?</Text>
                <Text style={styles.modalDialogText}>
                  Do you want to join <Text style={{ color: '#38BDF8', fontWeight: '700' }}>{confirmJoinItem.complaintNumber}</Text> ({confirmJoinItem.issueType})?
                </Text>
                <Text style={[styles.modalDialogText, { color: '#94A3B8', fontSize: 13, marginTop: 6 }]}>
                  You will receive real-time updates and notifications as this issue is resolved. A duplicate ticket will not be created.
                </Text>

                <View style={styles.modalBtnRow}>
                  <TouchableOpacity
                    style={styles.modalCancelBtn}
                    onPress={() => setConfirmJoinItem(null)}
                    disabled={joiningComplaintId !== null}>
                    <Text style={styles.modalCancelBtnText}>Cancel</Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={styles.modalConfirmBtn}
                    onPress={() => handleConfirmJoin(confirmJoinItem.id)}
                    disabled={joiningComplaintId !== null}>
                    {joiningComplaintId !== null ? (
                      <ActivityIndicator size="small" color="#FFFFFF" />
                    ) : (
                      <Text style={styles.modalConfirmBtnText}>Confirm Join</Text>
                    )}
                  </TouchableOpacity>
                </View>
              </View>
            </View>
          </Modal>
        )}
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#0B1120',
  },
  container: {
    flex: 1,
    backgroundColor: '#0B1120',
    justifyContent: 'center',
  },
  card: {
    backgroundColor: '#0F172A',
    borderRadius: 16,
    padding: 24,
    marginHorizontal: 20,
    borderWidth: 1,
    borderColor: '#1E293B',
  },
  headerContainer: {
    alignItems: 'center',
    marginBottom: 24,
  },
  appName: {
    fontSize: 28,
    fontWeight: '800',
    color: '#38BDF8',
    letterSpacing: 0.5,
  },
  appTagline: {
    fontSize: 13,
    color: '#94A3B8',
    marginTop: 4,
  },
  formGroup: {
    marginBottom: 16,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    color: '#CBD5E1',
    marginBottom: 8,
  },
  phoneInputRow: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#1E293B',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#334155',
    overflow: 'hidden',
  },
  countryCodeBox: {
    paddingHorizontal: 14,
    paddingVertical: 12,
    borderRightWidth: 1,
    borderRightColor: '#334155',
  },
  countryCodeText: {
    color: '#94A3B8',
    fontWeight: '700',
    fontSize: 15,
  },
  phoneInput: {
    flex: 1,
    paddingHorizontal: 14,
    paddingVertical: 12,
    color: '#F8FAFC',
    fontSize: 15,
    letterSpacing: 1,
  },
  otpNotice: {
    color: '#94A3B8',
    fontSize: 13,
    textAlign: 'center',
    marginBottom: 16,
  },
  otpInput: {
    backgroundColor: '#1E293B',
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#334155',
    color: '#38BDF8',
    fontSize: 22,
    fontWeight: '800',
    letterSpacing: 8,
    paddingVertical: 12,
  },
  primaryButton: {
    backgroundColor: '#0284C7',
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryButtonText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 15,
    letterSpacing: 0.5,
  },
  secondaryButton: {
    marginTop: 12,
    alignItems: 'center',
    paddingVertical: 8,
  },
  secondaryButtonText: {
    color: '#38BDF8',
    fontSize: 13,
    fontWeight: '600',
  },
  buttonDisabled: {
    opacity: 0.5,
  },
  errorBox: {
    backgroundColor: 'rgba(239, 68, 68, 0.15)',
    borderWidth: 1,
    borderColor: '#EF444466',
    borderRadius: 8,
    padding: 10,
    marginBottom: 14,
  },
  errorText: {
    color: '#F87171',
    fontSize: 12,
    textAlign: 'center',
  },
  fullDashboard: {
    flex: 1,
  },
  topHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 14,
    backgroundColor: '#0F172A',
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  dashboardTitle: {
    fontSize: 18,
    fontWeight: '800',
    color: '#38BDF8',
  },
  dashboardSubtitle: {
    fontSize: 11,
    color: '#94A3B8',
    marginTop: 2,
  },
  headerLogoutBtn: {
    backgroundColor: '#334155',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
  },
  headerLogoutBtnText: {
    color: '#F8FAFC',
    fontSize: 12,
    fontWeight: '600',
  },
  tabsRow: {
    flexDirection: 'row',
    backgroundColor: '#0F172A',
    borderBottomWidth: 1,
    borderBottomColor: '#1E293B',
  },
  tabBtn: {
    flex: 1,
    paddingVertical: 12,
    alignItems: 'center',
    borderBottomWidth: 2,
    borderBottomColor: 'transparent',
  },
  tabBtnActive: {
    borderBottomColor: '#38BDF8',
  },
  tabBtnText: {
    color: '#64748B',
    fontSize: 13,
    fontWeight: '600',
  },
  tabBtnTextActive: {
    color: '#38BDF8',
    fontWeight: '700',
  },
  tabContent: {
    flex: 1,
    padding: 16,
  },
  tabScrollContent: {
    paddingBottom: 120,
  },
  formCard: {
    backgroundColor: '#0F172A',
    borderRadius: 14,
    padding: 18,
    borderWidth: 1,
    borderColor: '#1E293B',
    marginBottom: 20,
  },
  cardHeaderTitle: {
    fontSize: 18,
    fontWeight: '800',
    color: '#F8FAFC',
  },
  cardHeaderSub: {
    fontSize: 12,
    color: '#94A3B8',
    marginTop: 2,
    marginBottom: 14,
  },
  inputLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: '#CBD5E1',
    marginBottom: 8,
  },
  typeGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  typeChip: {
    backgroundColor: '#1E293B',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
  },
  typeChipSelected: {
    backgroundColor: '#0284C733',
    borderColor: '#38BDF8',
  },
  typeChipText: {
    color: '#94A3B8',
    fontSize: 12,
  },
  typeChipTextSelected: {
    color: '#38BDF8',
    fontWeight: '700',
  },
  cameraTriggerBtn: {
    backgroundColor: '#1E293B',
    borderWidth: 2,
    borderStyle: 'dashed',
    borderColor: '#38BDF866',
    borderRadius: 12,
    paddingVertical: 24,
    alignItems: 'center',
    justifyContent: 'center',
  },
  cameraIcon: {
    fontSize: 32,
    marginBottom: 6,
  },
  cameraTriggerText: {
    color: '#38BDF8',
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  cameraSubtext: {
    color: '#64748B',
    fontSize: 11,
    marginTop: 4,
  },
  previewContainer: {
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: '#1E293B',
  },
  photoPreview: {
    width: '100%',
    height: 220,
    resizeMode: 'cover',
  },
  previewActionsRow: {
    flexDirection: 'row',
    gap: 8,
    padding: 10,
  },
  previewActionBtn: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 8,
    alignItems: 'center',
  },
  previewActionBtnText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 13,
  },
  gpsBoxLoading: {
    backgroundColor: '#1E293B',
    borderRadius: 10,
    padding: 16,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 12,
    borderWidth: 1,
    borderColor: '#38BDF866',
    marginTop: 8,
  },
  gpsWaitingText: {
    color: '#38BDF8',
    fontSize: 13,
    fontWeight: '600',
  },
  gpsBoxSuccess: {
    backgroundColor: '#064E3B44',
    borderWidth: 1.5,
    borderColor: '#10B981',
    borderRadius: 10,
    padding: 14,
    marginTop: 8,
  },
  gpsSuccessHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#10B98133',
    paddingBottom: 6,
  },
  gpsTag: {
    color: '#34D399',
    fontSize: 13,
    fontWeight: '800',
    letterSpacing: 0.3,
  },
  gpsRefreshBtn: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    backgroundColor: '#065F46',
    borderRadius: 4,
  },
  gpsRefreshText: {
    color: '#A7F3D0',
    fontSize: 11,
    fontWeight: '600',
  },
  gpsCoordsCard: {
    gap: 6,
    marginTop: 4,
  },
  gpsCoordRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  gpsCoordLabel: {
    color: '#94A3B8',
    fontSize: 13,
    fontWeight: '600',
  },
  gpsCoordValue: {
    color: '#FFFFFF',
    fontSize: 14,
    fontWeight: '800',
    fontFamily: Platform.OS === 'android' ? 'monospace' : 'Courier',
    letterSpacing: 0.5,
  },
  gpsBoxError: {
    backgroundColor: '#7F1D1D33',
    borderWidth: 1,
    borderColor: '#EF444488',
    borderRadius: 10,
    padding: 14,
    alignItems: 'center',
    marginTop: 8,
  },
  gpsErrorText: {
    color: '#FCA5A5',
    fontSize: 13,
    textAlign: 'center',
    marginBottom: 10,
    lineHeight: 18,
  },
  retryGpsBtn: {
    backgroundColor: '#EF4444',
    paddingHorizontal: 20,
    paddingVertical: 8,
    borderRadius: 6,
    alignItems: 'center',
  },
  retryGpsBtnText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 13,
  },
  submitNoticeBox: {
    backgroundColor: '#1E293B',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
    padding: 12,
    marginTop: 16,
    alignItems: 'center',
  },
  submitNoticeText: {
    color: '#94A3B8',
    fontSize: 12,
    textAlign: 'center',
    lineHeight: 18,
  },
  submitButton: {
    backgroundColor: '#0284C7',
    borderRadius: 12,
    paddingVertical: 16,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 20,
    marginBottom: 12,
    elevation: 3,
  },
  submitButtonDisabled: {
    backgroundColor: '#1E293B',
    borderWidth: 1,
    borderColor: '#334155',
    elevation: 0,
  },
  submitButtonText: {
    color: '#FFFFFF',
    fontWeight: '800',
    fontSize: 15,
    letterSpacing: 0.5,
  },
  submitButtonTextDisabled: {
    color: '#64748B',
  },
  submittingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 10,
  },
  successCard: {
    backgroundColor: '#0F172A',
    borderRadius: 14,
    padding: 24,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#10B98166',
  },
  successCheckIcon: {
    fontSize: 48,
    color: '#10B981',
    marginBottom: 8,
  },
  successCardTitle: {
    fontSize: 18,
    fontWeight: '800',
    color: '#F8FAFC',
    textAlign: 'center',
  },
  complaintNumBox: {
    backgroundColor: '#1E293B',
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 6,
    color: '#38BDF8',
    fontWeight: '700',
    fontSize: 13,
    marginVertical: 12,
  },
  successDesc: {
    color: '#94A3B8',
    fontSize: 13,
    textAlign: 'center',
    lineHeight: 18,
    marginBottom: 20,
  },
  listHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 14,
  },
  listHeaderTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: '#F8FAFC',
  },
  refreshBtn: {
    backgroundColor: '#1E293B',
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderRadius: 6,
  },
  refreshBtnText: {
    color: '#38BDF8',
    fontSize: 12,
  },
  emptyCard: {
    backgroundColor: '#0F172A',
    borderRadius: 14,
    padding: 32,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#1E293B',
  },
  emptyIcon: {
    fontSize: 40,
    marginBottom: 10,
  },
  emptyTitle: {
    color: '#F8FAFC',
    fontSize: 16,
    fontWeight: '700',
  },
  emptySub: {
    color: '#64748B',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 6,
  },
  complaintCard: {
    backgroundColor: '#0F172A',
    borderRadius: 14,
    padding: 16,
    marginBottom: 14,
    borderWidth: 1,
    borderColor: '#1E293B',
  },
  complaintCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 10,
  },
  complaintNumber: {
    fontSize: 15,
    fontWeight: '800',
    color: '#F8FAFC',
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 6,
    borderWidth: 1,
  },
  statusBadgeText: {
    fontSize: 10,
    fontWeight: '800',
  },
  cardImageContainer: {
    height: 160,
    borderRadius: 8,
    overflow: 'hidden',
    backgroundColor: '#1E293B',
    marginBottom: 12,
  },
  cardImage: {
    width: '100%',
    height: '100%',
    resizeMode: 'cover',
  },
  cardDetailsBox: {
    gap: 4,
    marginBottom: 10,
  },
  detailLine: {
    fontSize: 12,
    color: '#CBD5E1',
  },
  detailLabel: {
    fontWeight: '700',
    color: '#94A3B8',
  },
  resolvedProofBox: {
    backgroundColor: '#064E3B33',
    borderWidth: 1,
    borderColor: '#10B98166',
    borderRadius: 8,
    padding: 10,
    marginVertical: 8,
  },
  resolvedProofTitle: {
    color: '#34D399',
    fontSize: 12,
    fontWeight: '700',
    marginBottom: 6,
  },
  proofImageThumb: {
    width: '100%',
    height: 120,
    borderRadius: 6,
    resizeMode: 'cover',
  },
  toggleHistoryBtn: {
    paddingVertical: 6,
    borderTopWidth: 1,
    borderTopColor: '#1E293B',
    marginTop: 6,
    alignItems: 'center',
  },
  toggleHistoryText: {
    color: '#38BDF8',
    fontSize: 11,
    fontWeight: '600',
  },
  historyContainer: {
    marginTop: 8,
    paddingTop: 8,
    borderTopWidth: 1,
    borderTopColor: '#1E293B',
    gap: 8,
  },
  historyItem: {
    flexDirection: 'row',
    gap: 8,
  },
  historyDot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: '#38BDF8',
    marginTop: 4,
  },
  historyContent: {
    flex: 1,
  },
  historyStatusText: {
    color: '#F8FAFC',
    fontSize: 12,
    fontWeight: '700',
  },
  historyMetaText: {
    color: '#64748B',
    fontSize: 10,
  },
  historyRemarksText: {
    color: '#94A3B8',
    fontSize: 11,
    fontStyle: 'italic',
    marginTop: 2,
  },
  tabBtnActiveWorker: {
    borderBottomColor: '#10B981',
  },
  tabBtnTextActiveWorker: {
    color: '#10B981',
    fontWeight: '700',
  },
  workerSuccessBox: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    backgroundColor: 'rgba(16, 185, 129, 0.15)',
    borderWidth: 1,
    borderColor: '#10B98166',
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 10,
    marginHorizontal: 16,
    marginTop: 10,
  },
  workerSuccessText: {
    color: '#34D399',
    fontSize: 12,
    fontWeight: '600',
    flex: 1,
  },
  workerDismissText: {
    color: '#34D399',
    fontSize: 14,
    fontWeight: '800',
    marginLeft: 8,
    padding: 4,
  },
  workerActionContainer: {
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: '#334155',
  },
  workerStartWorkBtn: {
    backgroundColor: '#8B5CF6',
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  workerStartWorkBtnText: {
    color: '#FFFFFF',
    fontWeight: '800',
    fontSize: 14,
    letterSpacing: 0.5,
  },
  workerProofSection: {
    gap: 12,
  },
  workerProofSectionTitle: {
    fontSize: 13,
    fontWeight: '700',
    color: '#38BDF8',
    marginBottom: 2,
  },
  workerCameraBtn: {
    backgroundColor: '#1E293B',
    borderWidth: 2,
    borderStyle: 'dashed',
    borderColor: '#10B98188',
    borderRadius: 12,
    paddingVertical: 22,
    alignItems: 'center',
    justifyContent: 'center',
  },
  workerCameraIcon: {
    fontSize: 32,
    marginBottom: 6,
  },
  workerCameraBtnText: {
    color: '#10B981',
    fontSize: 14,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  workerPreviewContainer: {
    borderRadius: 10,
    overflow: 'hidden',
    backgroundColor: '#1E293B',
    borderWidth: 1.5,
    borderColor: '#10B981',
  },
  workerPhotoPreview: {
    width: '100%',
    height: 200,
    resizeMode: 'cover',
  },
  workerRetakeBtn: {
    backgroundColor: '#334155',
    paddingVertical: 10,
    alignItems: 'center',
  },
  workerRetakeBtnText: {
    color: '#FFFFFF',
    fontWeight: '700',
    fontSize: 13,
  },
  workerRemarksInput: {
    backgroundColor: '#1E293B',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#334155',
    color: '#F8FAFC',
    fontSize: 13,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  workerSubmitBtn: {
    backgroundColor: '#059669',
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center',
    justifyContent: 'center',
  },
  workerSubmitBtnDisabled: {
    backgroundColor: '#1E293B',
    borderWidth: 1,
    borderColor: '#334155',
  },
  workerSubmitBtnText: {
    color: '#FFFFFF',
    fontWeight: '800',
    fontSize: 14,
    letterSpacing: 0.5,
  },
  workerSubmitBtnTextDisabled: {
    color: '#64748B',
  },
  workerResolvedProofContainer: {
    backgroundColor: 'rgba(16, 185, 129, 0.1)',
    borderWidth: 1,
    borderColor: 'rgba(16, 185, 129, 0.3)',
    borderRadius: 8,
    padding: 10,
    marginTop: 8,
  },
  workerResolvedProofLabel: {
    fontSize: 12,
    fontWeight: '700',
    color: '#34D399',
    marginBottom: 6,
  },
  workerMapsBtn: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(56, 189, 248, 0.1)',
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.3)',
    paddingVertical: 5,
    paddingHorizontal: 10,
    borderRadius: 6,
    marginVertical: 4,
  },
  workerMapsBtnText: {
    color: '#38BDF8',
    fontSize: 12,
    fontWeight: '700',
  },
  workerLocationUnavailableBox: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(148, 163, 184, 0.1)',
    borderWidth: 1,
    borderColor: 'rgba(148, 163, 184, 0.2)',
    paddingVertical: 4,
    paddingHorizontal: 10,
    borderRadius: 6,
    marginVertical: 4,
  },
  workerLocationUnavailableText: {
    color: '#94A3B8',
    fontSize: 12,
  },
  // Similar Complaints / Duplicate Detection Styles
  similarContainer: {
    backgroundColor: '#1E293B',
    borderRadius: 16,
    padding: 18,
    borderWidth: 1.5,
    borderColor: '#38BDF8',
  },
  similarHeaderBanner: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    backgroundColor: 'rgba(56, 189, 248, 0.1)',
    borderRadius: 12,
    padding: 14,
    borderWidth: 1,
    borderColor: 'rgba(56, 189, 248, 0.3)',
    marginBottom: 12,
  },
  similarBannerIcon: {
    fontSize: 28,
  },
  similarBannerTitle: {
    color: '#38BDF8',
    fontSize: 17,
    fontWeight: '800',
  },
  similarBannerSub: {
    color: '#94A3B8',
    fontSize: 12,
    marginTop: 2,
    lineHeight: 16,
  },
  similarInstructionText: {
    color: '#CBD5E1',
    fontSize: 13,
    lineHeight: 18,
    marginBottom: 16,
  },
  similarCard: {
    backgroundColor: '#0F172A',
    borderRadius: 12,
    padding: 14,
    marginBottom: 14,
    borderWidth: 1,
    borderColor: '#334155',
  },
  similarCardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: 10,
  },
  similarComplaintNumber: {
    color: '#F8FAFC',
    fontSize: 16,
    fontWeight: '800',
  },
  similarDistanceBadge: {
    color: '#38BDF8',
    fontSize: 12,
    fontWeight: '700',
    marginTop: 2,
  },
  similarImageContainer: {
    height: 140,
    borderRadius: 8,
    overflow: 'hidden',
    backgroundColor: '#1E293B',
    marginBottom: 10,
  },
  similarImage: {
    width: '100%',
    height: '100%',
    resizeMode: 'cover',
  },
  similarMetaBox: {
    backgroundColor: 'rgba(30, 41, 59, 0.6)',
    borderRadius: 8,
    padding: 10,
    gap: 4,
    marginBottom: 10,
  },
  similarMetaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
  },
  similarMetaLabel: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '600',
  },
  similarResolvedNotice: {
    backgroundColor: 'rgba(16, 185, 129, 0.1)',
    borderWidth: 1,
    borderColor: 'rgba(16, 185, 129, 0.3)',
    borderRadius: 8,
    padding: 10,
    marginBottom: 10,
  },
  similarActionRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 4,
  },
  similarDetailsBtn: {
    flex: 1,
    borderWidth: 1,
    borderColor: '#475569',
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
    backgroundColor: '#1E293B',
  },
  similarDetailsBtnText: {
    color: '#38BDF8',
    fontSize: 13,
    fontWeight: '700',
  },
  similarJoinBtn: {
    flex: 1.3,
    backgroundColor: '#0284C7',
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  similarJoinBtnText: {
    color: '#FFFFFF',
    fontSize: 13,
    fontWeight: '800',
  },
  alreadyJoinedBadge: {
    flex: 1.3,
    backgroundColor: 'rgba(16, 185, 129, 0.15)',
    borderWidth: 1,
    borderColor: '#10B981',
    borderRadius: 8,
    paddingVertical: 10,
    alignItems: 'center',
    justifyContent: 'center',
  },
  alreadyJoinedText: {
    color: '#34D399',
    fontSize: 12,
    fontWeight: '800',
  },
  similarExpandedBox: {
    marginTop: 10,
    borderTopWidth: 1,
    borderTopColor: '#334155',
    paddingTop: 10,
    gap: 4,
  },
  similarExpandedTitle: {
    color: '#F8FAFC',
    fontSize: 13,
    fontWeight: '700',
    marginBottom: 4,
  },
  similarExpandedText: {
    color: '#94A3B8',
    fontSize: 12,
  },
  noneOfTheseBtn: {
    backgroundColor: '#4338CA',
    borderRadius: 10,
    paddingVertical: 14,
    alignItems: 'center',
    marginTop: 6,
  },
  noneOfTheseBtnText: {
    color: '#FFFFFF',
    fontWeight: '800',
    fontSize: 14,
  },
  cancelCheckBtn: {
    paddingVertical: 12,
    alignItems: 'center',
    marginTop: 4,
  },
  cancelCheckBtnText: {
    color: '#94A3B8',
    fontSize: 13,
    fontWeight: '600',
  },
  joinedStatsBox: {
    backgroundColor: '#0F172A',
    borderRadius: 10,
    padding: 14,
    alignItems: 'center',
    marginVertical: 14,
    borderWidth: 1,
    borderColor: 'rgba(245, 158, 11, 0.3)',
  },
  joinedStatsNumber: {
    fontSize: 22,
    fontWeight: '900',
    color: '#FBBF24',
  },
  joinedStatsLabel: {
    color: '#94A3B8',
    fontSize: 12,
    marginTop: 2,
  },
  // Join Confirmation Modal Styles
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.75)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  modalDialogCard: {
    backgroundColor: '#1E293B',
    borderRadius: 16,
    padding: 22,
    width: '100%',
    maxWidth: 420,
    borderWidth: 1,
    borderColor: '#38BDF8',
  },
  modalDialogTitle: {
    color: '#F8FAFC',
    fontSize: 18,
    fontWeight: '800',
    marginBottom: 10,
  },
  modalDialogText: {
    color: '#E2E8F0',
    fontSize: 14,
    lineHeight: 20,
  },
  modalBtnRow: {
    flexDirection: 'row',
    gap: 12,
    marginTop: 20,
  },
  modalCancelBtn: {
    flex: 1,
    backgroundColor: '#334155',
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
  },
  modalCancelBtnText: {
    color: '#CBD5E1',
    fontWeight: '700',
    fontSize: 14,
  },
  modalConfirmBtn: {
    flex: 1.4,
    backgroundColor: '#0284C7',
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  modalConfirmBtnText: {
    color: '#FFFFFF',
    fontWeight: '800',
    fontSize: 14,
  },
  // My Complaints Filter Row & Badges
  complaintFilterRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 14,
  },
  filterChip: {
    backgroundColor: '#1E293B',
    borderRadius: 20,
    paddingVertical: 6,
    paddingHorizontal: 12,
    borderWidth: 1,
    borderColor: '#334155',
  },
  filterChipActive: {
    backgroundColor: 'rgba(56, 189, 248, 0.15)',
    borderColor: '#38BDF8',
  },
  filterChipText: {
    color: '#94A3B8',
    fontSize: 12,
    fontWeight: '600',
  },
  filterChipTextActive: {
    color: '#38BDF8',
    fontWeight: '700',
  },
  originBadgeReporter: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(59, 130, 246, 0.15)',
    borderWidth: 1,
    borderColor: 'rgba(59, 130, 246, 0.4)',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
    marginTop: 3,
  },
  originBadgeJoined: {
    alignSelf: 'flex-start',
    backgroundColor: 'rgba(16, 185, 129, 0.15)',
    borderWidth: 1,
    borderColor: 'rgba(16, 185, 129, 0.4)',
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
    marginTop: 3,
  },
  originBadgeText: {
    color: '#CBD5E1',
    fontSize: 10,
    fontWeight: '700',
  },
  communityImpactBanner: {
    backgroundColor: '#0F172A',
    borderRadius: 6,
    paddingVertical: 6,
    paddingHorizontal: 10,
    marginBottom: 8,
    borderLeftWidth: 3,
    borderLeftColor: '#F59E0B',
  },
  communityImpactText: {
    color: '#CBD5E1',
    fontSize: 12,
    fontWeight: '600',
  },
});

