import { API_BASE_URL } from '../config/api';

export interface StatusHistoryItem {
  id: number;
  oldStatus?: string;
  newStatus: string;
  changedByMobile?: string;
  changedByName?: string;
  changedAt: string;
  remarks?: string;
}

export interface ComplaintData {
  id: number;
  complaintNumber: string;
  issueType: string;
  status: string;
  severity?: string;
  priority?: string;
  latitude: number;
  longitude: number;
  imageUrl: string;
  proofImageUrl?: string;
  confidenceScore?: number;
  createdAt: string;
  updatedAt?: string;
  slaHours?: number;
  slaDeadline?: string;
  slaStatus?: string;
  remainingMinutes?: number;
  resolvedAt?: string;
  resolvedWithinSla?: boolean;
  citizenMobile?: string;
  citizenName?: string;
  departmentId?: number;
  departmentName?: string;
  supervisorMobile?: string;
  supervisorName?: string;
  assignedWorkerMobile?: string;
  assignedWorkerName?: string;
  statusHistory?: StatusHistoryItem[];
  // Duplicate / Join features
  joinCount?: number;
  hasJoined?: boolean;
  isOriginalReporter?: boolean;
  distanceMeters?: number;
}

export interface CreateComplaintPayload {
  imageUrl: string;
  latitude: number;
  longitude: number;
  issueType?: string;
  severity?: string;
}

export interface DuplicateComplaintSummary {
  id: number;
  complaintNumber: string;
  issueType: string;
  status: string;
  severity?: string;
  priority?: string;
  latitude: number;
  longitude: number;
  imageUrl: string;
  proofImageUrl?: string;
  distanceMeters: number;
  joinCount: number;
  hasJoined?: boolean;
  isOriginalReporter?: boolean;
  eligibleForJoin?: boolean;
  departmentName?: string;
  createdAt: string;
  slaDeadline?: string;
}

export interface CheckDuplicatePayload {
  issueType: string;
  latitude: number;
  longitude: number;
  imageUrl?: string;
  radiusMeters?: number;
}

export interface CheckDuplicateResponse {
  duplicateFound: boolean;
  matchCount: number;
  radiusMeters: number;
  matches: DuplicateComplaintSummary[];
}

export interface JoinComplaintResponse {
  complaintId: number;
  complaintNumber: string;
  message: string;
  joinCount: number;
  alreadyJoined: boolean;
  isOriginalReporter: boolean;
  joinedAt: string;
}

/**
 * Checks backend for existing complaints within radius matching the category
 */
export async function checkDuplicateComplaints(
  idToken: string,
  payload: CheckDuplicatePayload,
): Promise<CheckDuplicateResponse> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints/check-duplicates`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${idToken}`,
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to check duplicates (HTTP ${response.status})`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out during duplicate complaint check.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Links authenticated citizen to an existing complaint
 */
export async function joinComplaint(
  idToken: string,
  complaintId: number,
): Promise<JoinComplaintResponse> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints/${complaintId}/join`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to join complaint (HTTP ${response.status})`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out while joining complaint.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Fetches complaints that the authenticated citizen has joined
 */
export async function getJoinedComplaints(idToken: string): Promise<ComplaintData[]> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints/joined`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to fetch joined complaints. Status: ${response.status}`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out loading joined complaints.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Submits a new citizen complaint to Spring Boot backend
 */
export async function createComplaint(
  idToken: string,
  payload: CreateComplaintPayload,
): Promise<ComplaintData> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 20000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${idToken}`,
      },
      body: JSON.stringify(payload),
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      console.error('[COMPLAINT SUBMISSION] createComplaint failed with HTTP status:', response.status, 'body:', errJson);
      throw new Error(errJson?.message || `Failed to submit complaint (HTTP ${response.status})`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out submitting complaint. Please check network connection.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Fetches complaints submitted by the authenticated citizen
 */
export async function getMyComplaints(idToken: string): Promise<ComplaintData[]> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints/my`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to fetch complaints. Status: ${response.status}`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out loading complaints.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Fetches single complaint details with status history
 */
export async function getComplaintDetails(
  idToken: string,
  complaintId: number,
): Promise<ComplaintData> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints/${complaintId}`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to fetch details. Status: ${response.status}`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out loading complaint details.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Uploads image file or base64 to backend
 */
export async function uploadImage(
  idToken: string,
  fileUri: string,
  fileType: string = 'image/jpeg',
): Promise<string> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 30000);

  const formData = new FormData();
  formData.append('file', {
    uri: fileUri,
    name: 'complaint.jpg',
    type: fileType,
  } as any);

  try {
    const response = await fetch(`${API_BASE_URL}/api/complaints/upload-image`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      body: formData,
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      console.error('[COMPLAINT SUBMISSION] uploadImage failed with HTTP status:', response.status, 'body:', errJson);
      throw new Error(errJson?.message || `Image upload failed (HTTP ${response.status})`);
    }

    const resJson = await response.json();
    return resJson.imageUrl;
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Image upload timed out. Please check server connection.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Registers device FCM token with backend for push notifications
 */
export async function registerFcmToken(
  idToken: string,
  fcmToken: string,
): Promise<void> {
  try {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 10000);

    const response = await fetch(`${API_BASE_URL}/api/users/fcm-token`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${idToken}`,
      },
      body: JSON.stringify({ fcmToken }),
      signal: controller.signal,
    });

    clearTimeout(timeoutId);

    if (!response.ok) {
      console.warn('[FCM] Token registration response status:', response.status);
    } else {
      console.log('[FCM] Token registered with backend successfully');
    }
  } catch (err: any) {
    console.warn('[FCM] Failed to register FCM token with backend:', err?.message);
  }
}

/**
 * Fetches complaints assigned to the authenticated field worker
 * GET /api/worker/complaints
 */
export async function getWorkerComplaints(idToken: string): Promise<ComplaintData[]> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/worker/complaints`, {
      method: 'GET',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to fetch worker complaints. Status: ${response.status}`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out loading worker tasks.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Updates status of assigned complaint by worker
 * PATCH /api/worker/complaints/{id}/status
 */
export async function updateWorkerStatus(
  idToken: string,
  complaintId: number,
  status: string,
  remarks: string = '',
): Promise<ComplaintData> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    const response = await fetch(`${API_BASE_URL}/api/worker/complaints/${complaintId}/status`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${idToken}`,
      },
      body: JSON.stringify({ status, remarks }),
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to update status. Status: ${response.status}`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Connection timed out updating status.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}

/**
 * Uploads worker resolution proof photo (live camera captured) to Spring Boot backend
 * POST /api/worker/complaints/{id}/proof
 */
export async function uploadWorkerProofImage(
  idToken: string,
  complaintId: number,
  fileUri: string,
  fileType: string = 'image/jpeg',
): Promise<ComplaintData> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 30000);

  const formData = new FormData();
  formData.append('file', {
    uri: fileUri,
    name: `proof_${complaintId}.jpg`,
    type: fileType,
  } as any);

  try {
    const response = await fetch(`${API_BASE_URL}/api/worker/complaints/${complaintId}/proof`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${idToken}`,
      },
      body: formData,
      signal: controller.signal,
    });

    if (!response.ok) {
      const errJson = await response.json().catch(() => null);
      throw new Error(errJson?.message || `Failed to upload proof photo (HTTP ${response.status})`);
    }

    return await response.json();
  } catch (err: any) {
    if (err.name === 'AbortError') {
      throw new Error('Proof photo upload timed out. Please check your network connection.');
    }
    throw err;
  } finally {
    clearTimeout(timeoutId);
  }
}
