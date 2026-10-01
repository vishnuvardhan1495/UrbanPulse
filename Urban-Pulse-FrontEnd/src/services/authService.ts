import { API_BASE_URL } from '../config/api';

export interface BackendAuthResponse {
  authenticated: boolean;
  isNewUser: boolean;
  firebaseUid: string;
  mobileNumber: string;
  role: string;
  name?: string;
  departmentId?: number;
  departmentName?: string;
  message?: string;
}

/**
 * Sends the verified Firebase ID token to the Spring Boot backend
 * endpoint: POST /api/auth/login
 *
 * Does NOT send mobileNumber separately.
 * The backend verifies the token and retrieves the mobile number directly.
 * Never logs or prints the ID token.
 */
export async function authenticateWithBackend(idToken: string): Promise<BackendAuthResponse> {
  const loginUrl = `${API_BASE_URL}/api/auth/login`;
  let response: Response;
  console.log(`[AUTH-DEBUG] BACKEND REQUEST STARTED: ${loginUrl}`);

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), 15000);

  try {
    response = await fetch(loginUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({ idToken }),
      signal: controller.signal,
    });
    console.log(`[AUTH-DEBUG] BACKEND RESPONSE RECEIVED`);
    console.log(`[AUTH-DEBUG] BACKEND STATUS: ${response.status}`);
  } catch (netErr: any) {
    console.error('[AUTH-DEBUG] Network fetch error connecting to backend:', netErr?.message || netErr);
    if (netErr?.name === 'AbortError') {
      throw new Error(
        `Connection to backend server at ${loginUrl} timed out after 15 seconds. Please verify your device is connected to the same Wi-Fi network and Spring Boot server is running.`,
      );
    }
    throw new Error(
      `Failed to connect to backend server at ${loginUrl}: ${netErr?.message || 'Network error'}. Please verify your device is on the same Wi-Fi network and Spring Boot server is running on port 8080.`,
    );
  } finally {
    clearTimeout(timeoutId);
  }

  let data: any = null;
  console.log('[AUTH-DEBUG] RESPONSE PARSING STARTED');
  try {
    data = await response.json();
    console.log('[AUTH-DEBUG] RESPONSE PARSED. Authenticated property:', data?.authenticated);
  } catch (jsonErr: any) {
    console.warn('[AUTH-DEBUG] Response body could not be parsed as JSON:', jsonErr?.message);
    data = null;
  }

  if (!response.ok) {
    const errorMessage =
      data?.message || `Backend authentication failed with HTTP status ${response.status}`;
    console.error(`[AUTH-DEBUG] Backend returned error: ${errorMessage}`);
    throw new Error(errorMessage);
  }

  return data as BackendAuthResponse;
}
