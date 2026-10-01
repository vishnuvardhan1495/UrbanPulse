import React, { useEffect, useState } from 'react';
import { getSupervisorComplaints, getDepartmentWorkers, assignWorker, createWorkerBySupervisor } from '../services/apiService';

export default function SupervisorView({ idToken, user }) {
  const [complaints, setComplaints] = useState([]);
  const [workers, setWorkers] = useState([]);
  const [selectedWorkers, setSelectedWorkers] = useState({});
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState({});
  const [message, setMessage] = useState(null);

  // Create Worker modal state
  const [showCreateWorkerModal, setShowCreateWorkerModal] = useState(false);
  const [workerForm, setWorkerForm] = useState({ name: '', mobileNumber: '' });
  const [createLoading, setCreateLoading] = useState(false);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    setSelectedWorkers({});
    try {
      const [compData, workerData] = await Promise.all([
        getSupervisorComplaints(idToken),
        getDepartmentWorkers(idToken),
      ]);
      setComplaints(compData);
      setWorkers(workerData);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  const handleAssign = async (complaintId) => {
    const workerMobile = selectedWorkers[complaintId];
    if (!workerMobile) {
      setMessage({ type: 'error', text: 'Please select a worker first.' });
      return;
    }

    setActionLoading((prev) => ({ ...prev, [complaintId]: true }));
    setMessage(null);

    try {
      const updated = await assignWorker(idToken, complaintId, workerMobile);
      setSelectedWorkers((prev) => {
        const copy = { ...prev };
        delete copy[complaintId];
        return copy;
      });
      setMessage({
        type: 'success',
        text: `Complaint ${updated.complaintNumber} assigned to worker ${updated.assignedWorkerName || updated.assignedWorkerMobile} successfully!`,
      });
      loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setActionLoading((prev) => ({ ...prev, [complaintId]: false }));
    }
  };

  const handleCreateWorker = async (e) => {
    e?.preventDefault();
    if (!workerForm.mobileNumber || workerForm.mobileNumber.length !== 10) {
      setMessage({ type: 'error', text: 'Please enter a valid 10-digit mobile number.' });
      return;
    }

    setCreateLoading(true);
    setMessage(null);

    try {
      await createWorkerBySupervisor(idToken, {
        name: workerForm.name.trim() || null,
        mobileNumber: workerForm.mobileNumber.trim(),
      });
      setMessage({
        type: 'success',
        text: `Worker ${workerForm.name || workerForm.mobileNumber} created successfully in ${user?.departmentName || 'your'} department!`,
      });
      setShowCreateWorkerModal(false);
      setWorkerForm({ name: '', mobileNumber: '' });
      loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setCreateLoading(false);
    }
  };

  // Metrics
  const totalCount = complaints.length;
  const forwardedCount = complaints.filter(c => c.status === 'FORWARDED').length;
  const assignedCount = complaints.filter(c => c.status === 'ASSIGNED').length;
  const inProgressCount = complaints.filter(c => c.status === 'IN_PROGRESS').length;
  const resolvedCount = complaints.filter(c => c.status === 'RESOLVED').length;
  const overdueCount = complaints.filter(c => c.slaStatus === 'OVERDUE').length;

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
      return <span style={{ backgroundColor: '#78350F', color: '#FBBF24', padding: '3px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '700' }}>DUE SOON: {hours}h {mins % 60}m</span>;
    }
    const mins = Math.max(0, remainingMinutes || 0);
    const hours = Math.floor(mins / 60);
    return <span style={{ backgroundColor: '#064E3B', color: '#6EE7B7', padding: '3px 8px', borderRadius: '6px', fontSize: '11px', fontWeight: '700' }}>ON TIME: {hours}h {mins % 60}m</span>;
  };

  return (
    <div style={{ padding: '24px', maxWidth: '1400px', margin: '0 auto' }}>
      {/* Top Header */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h2 style={{ fontSize: '24px', fontWeight: '800', color: '#A855F7' }}>Supervisor Department Dashboard</h2>
          <p style={{ fontSize: '13px', color: '#94A3B8' }}>
            Department: <strong style={{ color: '#F8FAFC' }}>{user?.departmentName || 'Assigned Department'}</strong> | Field worker assignment & dispatch
          </p>
        </div>
        <div style={{ display: 'flex', gap: '10px' }}>
          <button onClick={() => setShowCreateWorkerModal(true)} className="btn-primary" style={{ backgroundColor: '#A855F7' }}>
            + Create Worker
          </button>
          <button onClick={loadData} className="btn-primary" style={{ backgroundColor: '#334155' }}>
            🔄 Refresh
          </button>
        </div>
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

      {/* Department Summary Counters */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))', gap: '12px', marginBottom: '20px' }}>
        <div className="glass-card" style={{ padding: '12px', textAlign: 'center' }}>
          <div style={{ fontSize: '11px', color: '#94A3B8', fontWeight: '600' }}>DEPT TOTAL</div>
          <div style={{ fontSize: '22px', fontWeight: '800', color: '#F8FAFC' }}>{totalCount}</div>
        </div>
        <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#3B82F666' }}>
          <div style={{ fontSize: '11px', color: '#60A5FA', fontWeight: '600' }}>NEEDS ASSIGNMENT</div>
          <div style={{ fontSize: '22px', fontWeight: '800', color: '#93C5FD' }}>{forwardedCount}</div>
        </div>
        <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#A855F766' }}>
          <div style={{ fontSize: '11px', color: '#C084FC', fontWeight: '600' }}>ASSIGNED</div>
          <div style={{ fontSize: '22px', fontWeight: '800', color: '#E9D5FF' }}>{assignedCount}</div>
        </div>
        <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#E879F966' }}>
          <div style={{ fontSize: '11px', color: '#F0ABFC', fontWeight: '600' }}>IN PROGRESS</div>
          <div style={{ fontSize: '22px', fontWeight: '800', color: '#F5D0FE' }}>{inProgressCount}</div>
        </div>
        <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#10B98166' }}>
          <div style={{ fontSize: '11px', color: '#34D399', fontWeight: '600' }}>RESOLVED</div>
          <div style={{ fontSize: '22px', fontWeight: '800', color: '#6EE7B7' }}>{resolvedCount}</div>
        </div>
        <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#EF444466' }}>
          <div style={{ fontSize: '11px', color: '#F87171', fontWeight: '600' }}>OVERDUE</div>
          <div style={{ fontSize: '22px', fontWeight: '800', color: '#F87171' }}>{overdueCount}</div>
        </div>
      </div>

      {/* Department Workers Workload Bar */}
      <div className="glass-card" style={{ padding: '14px 18px', marginBottom: '20px' }}>
        <h4 style={{ fontSize: '13px', fontWeight: '700', color: '#CBD5E1', marginBottom: '8px' }}>
          Department Field Workers ({workers.length}) & Workload:
        </h4>
        {workers.length === 0 ? (
          <div style={{ fontSize: '12px', color: '#94A3B8' }}>
            No field workers registered in your department yet. Click "+ Create Worker" above to add staff.
          </div>
        ) : (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '10px' }}>
            {workers.map((w) => {
              const activeTasks = complaints.filter(c => c.assignedWorkerMobile === w.mobileNumber && c.status !== 'RESOLVED').length;
              return (
                <div key={w.mobileNumber} style={{
                  backgroundColor: '#1E293B',
                  border: '1px solid #334155',
                  padding: '6px 12px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  display: 'flex',
                  alignItems: 'center',
                  gap: '8px'
                }}>
                  <span style={{ color: '#F8FAFC', fontWeight: '600' }}>{w.name || 'Worker'}</span>
                  <span style={{ color: '#94A3B8', fontSize: '11px' }}>({w.mobileNumber})</span>
                  <span style={{
                    backgroundColor: activeTasks > 0 ? '#78350F' : '#064E3B',
                    color: activeTasks > 0 ? '#FBBF24' : '#34D399',
                    fontSize: '10px',
                    fontWeight: '700',
                    padding: '2px 6px',
                    borderRadius: '10px'
                  }}>
                    {activeTasks} Active Tasks
                  </span>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Complaints Grid */}
      {loading ? (
        <div style={{ textAlign: 'center', padding: '40px', color: '#94A3B8' }}>Loading Department Complaints...</div>
      ) : complaints.length === 0 ? (
        <div className="glass-card" style={{ padding: '40px', textAlign: 'center', color: '#94A3B8' }}>
          No complaints forwarded to your department yet.
        </div>
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))', gap: '20px' }}>
          {complaints.map((item) => {
            const hasAssignedWorker = Boolean(item.assignedWorkerMobile || item.assignedWorkerName);
            const isResolved = item.status === 'RESOLVED';
            const isInProgress = item.status === 'IN_PROGRESS';
            const isAssigned = item.status === 'ASSIGNED';
            const isForwarded = item.status === 'FORWARDED';

            // Helper to format backend URLs
            const formatImageUrl = (url) => {
              if (!url) return null;
              return url.startsWith('/') ? `http://localhost:8080${url}` : url;
            };

            // Resolution remarks from status history if available
            const resolutionHistory = item.statusHistory?.slice().reverse().find(h => h.newStatus === 'RESOLVED');
            const workerRemarks = resolutionHistory?.remarks;

            return (
              <div key={item.id} className="glass-card" style={{ padding: '18px', display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
                <div>
                  {/* Header: Complaint Number and Badges */}
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
                    <span style={{ fontSize: '17px', fontWeight: '800', color: '#F8FAFC' }}>{item.complaintNumber}</span>
                    <div style={{ display: 'flex', gap: '6px' }}>
                      <span className={`badge badge-${item.status.toLowerCase()}`}>{item.status}</span>
                      {getSlaBadge(item.slaStatus, item.remainingMinutes)}
                    </div>
                  </div>

                  {/* Image Display Section */}
                  {isResolved || item.proofImageUrl ? (
                    <div style={{ display: 'flex', gap: '10px', marginBottom: '12px' }}>
                      {/* Citizen Photo */}
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: '10px', fontWeight: '700', color: '#94A3B8', marginBottom: '4px', textTransform: 'uppercase' }}>
                          Citizen Photo:
                        </div>
                        <div style={{ height: '140px', borderRadius: '6px', overflow: 'hidden', backgroundColor: '#0F172A', border: '1px solid #334155' }}>
                          <img
                            src={formatImageUrl(item.imageUrl)}
                            alt="Complaint Issue"
                            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                          />
                        </div>
                      </div>

                      {/* Worker Proof Photo */}
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: '10px', fontWeight: '700', color: item.proofImageUrl ? '#34D399' : '#94A3B8', marginBottom: '4px', textTransform: 'uppercase' }}>
                          Worker Proof:
                        </div>
                        {item.proofImageUrl ? (
                          <div style={{ height: '140px', borderRadius: '6px', overflow: 'hidden', backgroundColor: '#0F172A', border: '1px solid #10B981' }}>
                            <img
                              src={formatImageUrl(item.proofImageUrl)}
                              alt="Worker Resolution Proof"
                              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                            />
                          </div>
                        ) : (
                          <div style={{ height: '140px', borderRadius: '6px', border: '1px dashed #475569', display: 'flex', alignItems: 'center', justifyContent: 'center', backgroundColor: '#0F172A', padding: '8px', textAlign: 'center' }}>
                            <span style={{ fontSize: '11px', color: '#94A3B8' }}>No proof image submitted</span>
                          </div>
                        )}
                      </div>
                    </div>
                  ) : (
                    <div style={{ height: '160px', borderRadius: '6px', overflow: 'hidden', marginBottom: '12px', backgroundColor: '#0F172A' }}>
                      <img
                        src={formatImageUrl(item.imageUrl)}
                        alt="Complaint"
                        style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                      />
                    </div>
                  )}

                  {/* Complaint Details */}
                  <div style={{ fontSize: '12px', color: '#CBD5E1', display: 'flex', flexDirection: 'column', gap: '5px', marginBottom: '14px' }}>
                    <div><strong>Issue:</strong> <span style={{ color: '#38BDF8' }}>{item.issueType}</span> (SLA: {item.slaHours}h)</div>
                    <div><strong>Citizen:</strong> {item.citizenMobile} {item.citizenName ? `(${item.citizenName})` : ''}</div>
                    <div><strong>Location:</strong> 📍 Lat {item.latitude}, Lng {item.longitude}</div>
                    <div>
                      <strong>Assigned Worker:</strong>{' '}
                      {hasAssignedWorker ? (
                        <span style={{ color: '#F8FAFC', fontWeight: '600' }}>
                          {item.assignedWorkerName || 'Worker'} <span style={{ color: '#94A3B8', fontWeight: '400' }}>({item.assignedWorkerMobile})</span>
                        </span>
                      ) : (
                        <span style={{ color: '#F59E0B', fontWeight: '600' }}>⚠️ Unassigned</span>
                      )}
                    </div>
                    <div><strong>Created:</strong> {new Date(item.createdAt).toLocaleString()}</div>
                    {item.slaDeadline && <div><strong>SLA Deadline:</strong> {new Date(item.slaDeadline).toLocaleString()}</div>}

                    {/* Resolution Metadata Banner */}
                    {isResolved && (
                      <div style={{ backgroundColor: 'rgba(16, 185, 129, 0.1)', border: '1px solid rgba(16, 185, 129, 0.3)', borderRadius: '6px', padding: '8px 10px', marginTop: '6px', display: 'flex', flexDirection: 'column', gap: '3px' }}>
                        <div style={{ color: item.resolvedWithinSla ? '#34D399' : '#FBBF24', fontWeight: '700', fontSize: '12px' }}>
                          {item.resolvedWithinSla ? '✅ Resolved within SLA' : '⚠️ Resolved after SLA deadline'}
                        </div>
                        {item.resolvedAt && (
                          <div style={{ color: '#CBD5E1', fontSize: '11px' }}>
                            🕒 <strong>Resolved At:</strong> {new Date(item.resolvedAt).toLocaleString()}
                          </div>
                        )}
                        {workerRemarks && (
                          <div style={{ color: '#94A3B8', fontSize: '11px', fontStyle: 'italic', marginTop: '2px' }}>
                            💬 <strong>Remarks:</strong> "{workerRemarks}"
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                </div>

                {/* Bottom Action / Status Controls */}
                {isResolved ? (
                  <div style={{ borderTop: '1px solid rgba(16, 185, 129, 0.3)', paddingTop: '12px' }}>
                    <div style={{
                      backgroundColor: 'rgba(16, 185, 129, 0.08)',
                      border: '1px solid rgba(16, 185, 129, 0.25)',
                      borderRadius: '6px',
                      padding: '10px 14px',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      gap: '8px'
                    }}>
                      <div>
                        <div style={{ color: '#34D399', fontWeight: '700', fontSize: '12px', display: 'flex', alignItems: 'center', gap: '6px' }}>
                          <span>✅</span> Complaint Completed & Closed
                        </div>
                        <div style={{ color: '#94A3B8', fontSize: '11px', marginTop: '2px' }}>
                          Assigned to: <strong style={{ color: '#F8FAFC' }}>{item.assignedWorkerName || 'Worker'}</strong> ({item.assignedWorkerMobile})
                        </div>
                      </div>
                      <span style={{
                        backgroundColor: '#065F46',
                        color: '#34D399',
                        fontSize: '11px',
                        fontWeight: '700',
                        padding: '3px 8px',
                        borderRadius: '4px'
                      }}>
                        RESOLVED
                      </span>
                    </div>
                  </div>
                ) : isInProgress ? (
                  <div style={{ borderTop: '1px solid #334155', paddingTop: '12px' }}>
                    <div style={{
                      backgroundColor: 'rgba(232, 121, 249, 0.1)',
                      border: '1px solid rgba(232, 121, 249, 0.3)',
                      borderRadius: '6px',
                      padding: '8px 12px',
                      marginBottom: '10px',
                      display: 'flex',
                      alignItems: 'center',
                      gap: '8px'
                    }}>
                      <span style={{ fontSize: '14px' }}>⚙️</span>
                      <span style={{ fontSize: '12px', color: '#F5D0FE', fontWeight: '600' }}>
                        Work in progress with {item.assignedWorkerName || 'Worker'} ({item.assignedWorkerMobile})
                      </span>
                    </div>
                    <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '6px' }}>
                      Reassign Field Worker (If necessary):
                    </label>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <select
                        className="input-field"
                        style={{ flex: 1, fontSize: '12px' }}
                        value={selectedWorkers[item.id] || item.assignedWorkerMobile || ''}
                        onChange={(e) => setSelectedWorkers({ ...selectedWorkers, [item.id]: e.target.value })}
                      >
                        <option value="">-- Select Department Worker --</option>
                        {workers.map((w) => (
                          <option key={w.mobileNumber} value={w.mobileNumber}>
                            {w.name || 'Worker'} ({w.mobileNumber})
                          </option>
                        ))}
                      </select>
                      <button
                        className="btn-primary"
                        style={{ backgroundColor: '#E879F9', fontSize: '12px', padding: '6px 12px', color: '#0F172A', fontWeight: '700' }}
                        disabled={actionLoading[item.id]}
                        onClick={() => handleAssign(item.id)}
                      >
                        {actionLoading[item.id] ? 'Reassigning...' : 'REASSIGN'}
                      </button>
                    </div>
                  </div>
                ) : hasAssignedWorker ? (
                  <div style={{ borderTop: '1px solid #334155', paddingTop: '12px' }}>
                    <label style={{ fontSize: '11px', fontWeight: '600', color: '#CBD5E1', display: 'block', marginBottom: '6px' }}>
                      Reassign Field Worker:
                    </label>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <select
                        className="input-field"
                        style={{ flex: 1, fontSize: '12px' }}
                        value={selectedWorkers[item.id] || item.assignedWorkerMobile || ''}
                        onChange={(e) => setSelectedWorkers({ ...selectedWorkers, [item.id]: e.target.value })}
                      >
                        <option value="">-- Select Department Worker --</option>
                        {workers.map((w) => (
                          <option key={w.mobileNumber} value={w.mobileNumber}>
                            {w.name || 'Worker'} ({w.mobileNumber})
                          </option>
                        ))}
                      </select>
                      <button
                        className="btn-primary"
                        style={{ backgroundColor: '#A855F7', fontSize: '12px', padding: '6px 12px' }}
                        disabled={actionLoading[item.id]}
                        onClick={() => handleAssign(item.id)}
                      >
                        {actionLoading[item.id] ? 'Reassigning...' : 'REASSIGN'}
                      </button>
                    </div>
                  </div>
                ) : (
                  <div style={{ borderTop: '1px solid #334155', paddingTop: '12px' }}>
                    <label style={{ fontSize: '11px', fontWeight: '700', color: '#F59E0B', display: 'block', marginBottom: '6px' }}>
                      ⚠️ Action Required: Assign Field Worker:
                    </label>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <select
                        className="input-field"
                        style={{ flex: 1, fontSize: '12px', borderColor: '#F59E0B66' }}
                        value={selectedWorkers[item.id] || ''}
                        onChange={(e) => setSelectedWorkers({ ...selectedWorkers, [item.id]: e.target.value })}
                      >
                        <option value="">-- Select Department Worker --</option>
                        {workers.map((w) => (
                          <option key={w.mobileNumber} value={w.mobileNumber}>
                            {w.name || 'Worker'} ({w.mobileNumber})
                          </option>
                        ))}
                      </select>
                      <button
                        className="btn-primary"
                        style={{ backgroundColor: '#A855F7', fontSize: '12px', padding: '6px 12px', fontWeight: '700' }}
                        disabled={actionLoading[item.id]}
                        onClick={() => handleAssign(item.id)}
                      >
                        {actionLoading[item.id] ? 'Assigning...' : 'ASSIGN'}
                      </button>
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}

      {/* Create Worker Modal */}
      {showCreateWorkerModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.75)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '420px', padding: '24px' }}>
            <h3 style={{ fontSize: '18px', fontWeight: '700', color: '#F8FAFC', marginBottom: '6px' }}>
              Create Department Worker
            </h3>
            <p style={{ fontSize: '12px', color: '#94A3B8', marginBottom: '16px' }}>
              Department is locked to: <strong style={{ color: '#A855F7' }}>{user?.departmentName || 'Your Department'}</strong>
            </p>

            <form onSubmit={handleCreateWorker} style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              <div>
                <label style={{ fontSize: '12px', color: '#CBD5E1', display: 'block', marginBottom: '4px' }}>Full Name:</label>
                <input
                  type="text"
                  className="input-field"
                  style={{ width: '100%' }}
                  placeholder="e.g. Arun Kumar"
                  value={workerForm.name}
                  onChange={(e) => setWorkerForm({ ...workerForm, name: e.target.value })}
                  required
                />
              </div>

              <div>
                <label style={{ fontSize: '12px', color: '#CBD5E1', display: 'block', marginBottom: '4px' }}>10-Digit Mobile Number:</label>
                <div style={{ display: 'flex', gap: '8px' }}>
                  <span style={{ backgroundColor: '#1E293B', padding: '8px 12px', borderRadius: '6px', color: '#94A3B8', fontSize: '13px', fontWeight: '600' }}>+91</span>
                  <input
                    type="tel"
                    className="input-field"
                    style={{ flex: 1 }}
                    placeholder="9966843779"
                    maxLength={10}
                    value={workerForm.mobileNumber}
                    onChange={(e) => setWorkerForm({ ...workerForm, mobileNumber: e.target.value })}
                    required
                  />
                </div>
              </div>

              <div style={{ display: 'flex', gap: '10px', marginTop: '12px' }}>
                <button
                  type="button"
                  onClick={() => setShowCreateWorkerModal(false)}
                  style={{ flex: 1, padding: '8px', borderRadius: '6px', border: '1px solid #334155', background: '#1E293B', color: '#CBD5E1', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={createLoading}
                  style={{ flex: 1, backgroundColor: '#A855F7' }}
                >
                  {createLoading ? 'Creating...' : 'Create Worker'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
