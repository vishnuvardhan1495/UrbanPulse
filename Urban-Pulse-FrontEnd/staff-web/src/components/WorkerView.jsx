import React, { useEffect, useState } from 'react';
import { getWorkerComplaints, updateWorkerStatus, uploadProofImage } from '../services/apiService';

const isValidCoordinate = (lat, lng) => {
  if (lat === null || lat === undefined || lng === null || lng === undefined) return false;
  if (lat === '' || lng === '') return false;
  const numLat = Number(lat);
  const numLng = Number(lng);
  if (Number.isNaN(numLat) || Number.isNaN(numLng)) return false;
  if (numLat < -90 || numLat > 90) return false;
  if (numLng < -180 || numLng > 180) return false;
  return true;
};

export default function WorkerView({ idToken, user }) {
  const [complaints, setComplaints] = useState([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState({});
  const [proofFiles, setProofFiles] = useState({});
  const [remarksMap, setRemarksMap] = useState({});
  const [message, setMessage] = useState(null);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const compData = await getWorkerComplaints(idToken);
      setComplaints(compData);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  const handleStartWork = async (complaintId) => {
    setActionLoading((prev) => ({ ...prev, [complaintId]: true }));
    setMessage(null);
    try {
      const updated = await updateWorkerStatus(idToken, complaintId, 'IN_PROGRESS', 'Worker arrived on site and started repair');
      setMessage({ type: 'success', text: `Complaint ${updated.complaintNumber} is now IN_PROGRESS!` });
      loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setActionLoading((prev) => ({ ...prev, [complaintId]: false }));
    }
  };

  const handleResolve = async (complaintId) => {
    const file = proofFiles[complaintId];
    if (!file) {
      setMessage({ type: 'error', text: 'Please select a resolution proof photo before marking as RESOLVED.' });
      return;
    }

    setActionLoading((prev) => ({ ...prev, [complaintId]: true }));
    setMessage(null);

    try {
      // Step 1: Upload resolution proof image
      await uploadProofImage(idToken, complaintId, file);

      // Step 2: Update status to RESOLVED
      const remarks = remarksMap[complaintId] || 'Issue successfully fixed and verified on site.';
      const updated = await updateWorkerStatus(idToken, complaintId, 'RESOLVED', remarks);

      const slaMsg = updated.resolvedWithinSla ? '✅ Resolved within SLA!' : '⚠️ Resolved after SLA deadline.';
      setMessage({ type: 'success', text: `Complaint ${updated.complaintNumber} successfully RESOLVED! ${slaMsg}` });
      loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setActionLoading((prev) => ({ ...prev, [complaintId]: false }));
    }
  };

  const getSlaBadge = (status, remainingMinutes) => {
    if (status === 'RESOLVED') {
      return <span style={{ backgroundColor: '#065F46', color: '#34D399', padding: '3px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '700' }}>RESOLVED</span>;
    }
    if (status === 'OVERDUE') {
      const mins = Math.abs(remainingMinutes || 0);
      const hours = Math.floor(mins / 60);
      return <span style={{ backgroundColor: '#7F1D1D', color: '#F87171', padding: '3px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '700' }}>OVERDUE by {hours}h {mins % 60}m</span>;
    }
    if (status === 'DUE_SOON') {
      const mins = Math.max(0, remainingMinutes || 0);
      const hours = Math.floor(mins / 60);
      return <span style={{ backgroundColor: '#78350F', color: '#FBBF24', padding: '3px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '700' }}>DUE SOON: {hours}h {mins % 60}m left</span>;
    }
    const mins = Math.max(0, remainingMinutes || 0);
    const hours = Math.floor(mins / 60);
    return <span style={{ backgroundColor: '#064E3B', color: '#6EE7B7', padding: '3px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '700' }}>ON TIME: {hours}h {mins % 60}m left</span>;
  };

  return (
    <div style={{ padding: '24px', maxWidth: '1200px', margin: '0 auto' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '24px' }}>
        <div>
          <h2 style={{ fontSize: '24px', fontWeight: '800', color: '#10B981' }}>Field Worker Dispatch Dashboard</h2>
          <p style={{ fontSize: '14px', color: '#94A3B8' }}>My Assigned Complaints, Work Progression & Resolution Proof</p>
        </div>
        <button onClick={loadData} className="btn-primary" style={{ backgroundColor: '#334155' }}>
          🔄 Refresh Tasks
        </button>
      </div>

      {message && (
        <div style={{
          padding: '12px 16px',
          borderRadius: '8px',
          marginBottom: '20px',
          backgroundColor: message.type === 'error' ? 'rgba(239, 68, 68, 0.2)' : 'rgba(16, 185, 129, 0.2)',
          border: `1px solid ${message.type === 'error' ? '#EF4444' : '#10B981'}`,
          color: message.type === 'error' ? '#F87171' : '#34D399',
          fontSize: '13px'
        }}>
          {message.text}
        </div>
      )}

      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px', color: '#94A3B8' }}>Loading Assigned Tasks...</div>
      ) : complaints.length === 0 ? (
        <div className="glass-card" style={{ padding: '40px', textAlign: 'center', color: '#94A3B8' }}>
          No complaints assigned to you at the moment.
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(360px, 1fr))', gap: '20px' }}>
          {complaints.map((item) => (
            <div key={item.id} className="glass-card" style={{ padding: '20px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                  <span style={{ fontSize: '18px', fontWeight: '700', color: '#F8FAFC' }}>{item.complaintNumber}</span>
                  <div style={{ display: 'flex', gap: '6px' }}>
                    <span className={`badge badge-${item.status.toLowerCase()}`}>{item.status}</span>
                    {getSlaBadge(item.slaStatus, item.remainingMinutes)}
                  </div>
                </div>

                <div style={{ height: '170px', borderRadius: '8px', overflow: 'hidden', marginBottom: '14px', backgroundColor: '#0F172A' }}>
                  <img
                    src={item.imageUrl.startsWith('/') ? `http://localhost:8080${item.imageUrl}` : item.imageUrl}
                    alt="Complaint"
                    style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                  />
                </div>

                <div style={{ fontSize: '13px', color: '#CBD5E1', display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '16px' }}>
                  <div><strong>Issue:</strong> <span style={{ color: '#38BDF8' }}>{item.issueType}</span> (SLA: {item.slaHours}h)</div>
                  <div><strong>Location:</strong> 📍 Lat {item.latitude ?? 'N/A'}, Lng {item.longitude ?? 'N/A'}</div>
                  {isValidCoordinate(item.latitude, item.longitude) ? (
                    <a
                      href={`https://www.google.com/maps?q=${item.latitude},${item.longitude}`}
                      target="_blank"
                      rel="noopener noreferrer"
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '6px',
                        marginTop: '2px',
                        marginBottom: '2px',
                        padding: '6px 12px',
                        fontSize: '12px',
                        fontWeight: '600',
                        color: '#38BDF8',
                        backgroundColor: 'rgba(56, 189, 248, 0.1)',
                        border: '1px solid rgba(56, 189, 248, 0.3)',
                        borderRadius: '6px',
                        textDecoration: 'none',
                        width: 'fit-content',
                        cursor: 'pointer',
                        transition: 'all 0.2s ease',
                      }}
                      onMouseEnter={(e) => {
                        e.currentTarget.style.backgroundColor = 'rgba(56, 189, 248, 0.2)';
                        e.currentTarget.style.borderColor = '#38BDF8';
                      }}
                      onMouseLeave={(e) => {
                        e.currentTarget.style.backgroundColor = 'rgba(56, 189, 248, 0.1)';
                        e.currentTarget.style.borderColor = 'rgba(56, 189, 248, 0.3)';
                      }}
                    >
                      📍 View on Google Maps
                    </a>
                  ) : (
                    <span
                      style={{
                        display: 'inline-flex',
                        alignItems: 'center',
                        gap: '6px',
                        marginTop: '2px',
                        marginBottom: '2px',
                        padding: '6px 12px',
                        fontSize: '12px',
                        color: '#94A3B8',
                        backgroundColor: 'rgba(148, 163, 184, 0.1)',
                        border: '1px solid rgba(148, 163, 184, 0.2)',
                        borderRadius: '6px',
                        width: 'fit-content',
                      }}
                    >
                      Location unavailable
                    </span>
                  )}
                  <div><strong>Department:</strong> {item.departmentName}</div>
                  {item.slaDeadline && <div><strong>Deadline:</strong> {new Date(item.slaDeadline).toLocaleString()}</div>}
                  {item.resolvedAt && (
                    <div style={{ color: item.resolvedWithinSla ? '#34D399' : '#F87171', fontWeight: '600' }}>
                      {item.resolvedWithinSla ? '✅ Resolved within SLA' : '⚠️ Resolved after SLA deadline'} on {new Date(item.resolvedAt).toLocaleString()}
                    </div>
                  )}
                </div>

                {item.proofImageUrl && (
                  <div style={{ backgroundColor: 'rgba(16, 185, 129, 0.1)', border: '1px solid rgba(16, 185, 129, 0.3)', borderRadius: '8px', padding: '10px', marginBottom: '14px' }}>
                    <div style={{ fontSize: '12px', fontWeight: '700', color: '#34D399', marginBottom: '6px' }}>Uploaded Resolution Proof:</div>
                    <img
                      src={item.proofImageUrl.startsWith('/') ? `http://localhost:8080${item.proofImageUrl}` : item.proofImageUrl}
                      alt="Proof"
                      style={{ width: '100%', height: '120px', objectFit: 'cover', borderRadius: '6px' }}
                    />
                  </div>
                )}
              </div>

              <div style={{ borderTop: '1px solid #334155', paddingTop: '14px' }}>
                {item.status === 'ASSIGNED' && (
                  <button
                    className="btn-primary"
                    style={{ width: '100%', backgroundColor: '#E879F9' }}
                    disabled={actionLoading[item.id]}
                    onClick={() => handleStartWork(item.id)}
                  >
                    {actionLoading[item.id] ? 'Starting Work...' : '⚙️ START WORK (Set IN_PROGRESS)'}
                  </button>
                )}

                {item.status === 'IN_PROGRESS' && (
                  <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                    <label style={{ fontSize: '12px', fontWeight: '700', color: '#38BDF8' }}>
                      Resolution Proof Photo (Live Camera Only):
                    </label>

                    {/* Hidden camera input restricted to rear live camera capture */}
                    <input
                      type="file"
                      id={`camera-input-${item.id}`}
                      accept="image/*"
                      capture="environment"
                      style={{ display: 'none' }}
                      onChange={(e) => {
                        if (e.target.files && e.target.files[0]) {
                          setProofFiles({ ...proofFiles, [item.id]: e.target.files[0] });
                        }
                      }}
                    />

                    {!proofFiles[item.id] ? (
                      <button
                        type="button"
                        className="btn-primary"
                        style={{
                          backgroundColor: '#0284C7',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          gap: '8px',
                          padding: '14px',
                          fontWeight: '700',
                        }}
                        onClick={() => {
                          const inputEl = document.getElementById(`camera-input-${item.id}`);
                          if (inputEl) inputEl.click();
                        }}
                      >
                        <span style={{ fontSize: '18px' }}>📷</span> Take Proof Photo
                      </button>
                    ) : (
                      <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
                        <div style={{ borderRadius: '8px', overflow: 'hidden', height: '180px', backgroundColor: '#0F172A', border: '1.5px solid #10B981' }}>
                          <img
                            src={URL.createObjectURL(proofFiles[item.id])}
                            alt="Captured Proof Preview"
                            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                          />
                        </div>
                        <button
                          type="button"
                          className="btn-secondary"
                          style={{ backgroundColor: '#334155', color: '#F8FAFC', padding: '8px 12px', fontSize: '13px' }}
                          onClick={() => {
                            const inputEl = document.getElementById(`camera-input-${item.id}`);
                            if (inputEl) {
                              inputEl.value = '';
                              inputEl.click();
                            }
                          }}
                        >
                          🔄 Retake Photo
                        </button>
                      </div>
                    )}

                    <input
                      type="text"
                      className="input-field"
                      placeholder="Remarks (e.g. Repaired pothole with asphalt mix)"
                      value={remarksMap[item.id] || ''}
                      onChange={(e) => setRemarksMap({ ...remarksMap, [item.id]: e.target.value })}
                    />
                    <button
                      className="btn-success"
                      style={{ width: '100%', opacity: !proofFiles[item.id] ? 0.6 : 1 }}
                      disabled={actionLoading[item.id] || !proofFiles[item.id]}
                      onClick={() => handleResolve(item.id)}
                    >
                      {actionLoading[item.id] ? 'Submitting Proof & Resolving...' : '✅ Submit Proof & Mark Resolved'}
                    </button>
                  </div>
                )}

                {item.status === 'RESOLVED' && (
                  <div style={{ textAlign: 'center', color: '#34D399', fontWeight: '600', fontSize: '14px', padding: '6px' }}>
                    🎉 Complaint Resolved & Verified
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
