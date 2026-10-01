const API_BASE_URL = 'http://localhost:8080';

export async function loginWithToken(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ idToken }),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Login failed');
  }
  return await response.json();
}

export async function getAdminComplaints(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/admin/complaints`, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!response.ok) throw new Error('Failed to fetch admin complaints');
  return await response.json();
}

export async function forwardComplaint(idToken, complaintId, departmentId) {
  const response = await fetch(`${API_BASE_URL}/api/admin/complaints/${complaintId}/forward`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`,
    },
    body: JSON.stringify({ departmentId }),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to forward complaint');
  }
  return await response.json();
}

export async function getSupervisorComplaints(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/supervisor/complaints`, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!response.ok) throw new Error('Failed to fetch supervisor complaints');
  return await response.json();
}

export async function getDepartmentWorkers(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/users/workers`, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!response.ok) return [];
  return await response.json();
}

export async function assignWorker(idToken, complaintId, workerId) {
  const response = await fetch(`${API_BASE_URL}/api/supervisor/complaints/${complaintId}/assign`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`,
    },
    body: JSON.stringify({ workerId }),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to assign worker');
  }
  return await response.json();
}

export async function getWorkerComplaints(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/worker/complaints`, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!response.ok) throw new Error('Failed to fetch worker complaints');
  return await response.json();
}

export async function updateWorkerStatus(idToken, complaintId, status, remarks = '') {
  const response = await fetch(`${API_BASE_URL}/api/worker/complaints/${complaintId}/status`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`,
    },
    body: JSON.stringify({ status, remarks }),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to update status');
  }
  return await response.json();
}

export async function uploadProofImage(idToken, complaintId, file) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(`${API_BASE_URL}/api/worker/complaints/${complaintId}/proof`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${idToken}` },
    body: formData,
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to upload proof image');
  }
  return await response.json();
}

export async function getDepartments(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/departments`, {
    headers: { Authorization: `Bearer ${idToken}` },
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to fetch departments');
  }
  return await response.json();
}

export async function createDepartment(idToken, data) {
  const response = await fetch(`${API_BASE_URL}/api/departments`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`,
    },
    body: JSON.stringify(data),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to create department');
  }
  return await response.json();
}

export async function createStaffUser(idToken, data) {
  const response = await fetch(`${API_BASE_URL}/api/users/staff`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`,
    },
    body: JSON.stringify(data),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to create staff account');
  }
  return await response.json();
}

export async function createWorkerBySupervisor(idToken, data) {
  const response = await fetch(`${API_BASE_URL}/api/users/workers`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${idToken}`,
    },
    body: JSON.stringify(data),
  });
  if (!response.ok) {
    const err = await response.json().catch(() => null);
    throw new Error(err?.message || 'Failed to create worker account');
  }
  return await response.json();
}
