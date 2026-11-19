import React, { useEffect, useState } from 'react';
import {
  getAdminComplaints,
  forwardComplaint,
  manualAssignComplaint,
  approveComplaint,
  rejectComplaint,
  getDepartments,
  createStaffUser,
  createDepartment
} from '../services/apiService';

const CANONICAL_CATEGORIES = [
  'Damaged Concrete Structures',
  'Damaged Electrical Poles',
  'Damaged Road Signs',
  'Dead Animals Pollution',
  'Fallen Trees',
  'Garbage',
  'Illegal Parking',
  'Potholes and Road Cracks',
];

const CATEGORY_TO_DEPARTMENT = {
  'Damaged Concrete Structures': 'Engineering Department',
  'Damaged Electrical Poles': 'Electrical Department',
  'Damaged Road Signs': 'Road Safety Department',
  'Dead Animals Pollution': 'Public Health Department',
  'Fallen Trees': 'Horticulture Department',
  'Garbage': 'Sanitation Department',
  'Illegal Parking': 'Traffic Department',
  'Potholes and Road Cracks': 'Road Safety Department',
};

export default function AdminView({ idToken, user }) {
  const [complaints, setComplaints] = useState([]);
  const [departments, setDepartments] = useState([]);
  const [selectedDepts, setSelectedDepts] = useState({});
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState({});
  const [message, setMessage] = useState(null);

  // Active top-level section: 'ALL_GRIEVANCES' | 'NON_CIVIC_REVIEW'
  const [activeSection, setActiveSection] = useState('ALL_GRIEVANCES');

  // Filters for All Grievances
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [slaFilter, setSlaFilter] = useState('ALL');
  const [deptFilter, setDeptFilter] = useState('ALL');
  const [issueTypeFilter, setIssueTypeFilter] = useState('ALL');
  const [reviewFilter, setReviewFilter] = useState('ALL'); // 'ALL' | 'REVIEW_ONLY'

  // Filter for Low-Confidence Review section
  const [reviewTabFilter, setReviewTabFilter] = useState('PENDING'); // 'PENDING' | 'APPROVED' | 'REJECTED' | 'ALL'

  // Manual review modal state (general/legacy)
  const [reviewModalComplaint, setReviewModalComplaint] = useState(null);
  const [reviewForm, setReviewForm] = useState({ departmentId: '', supervisorMobile: '', category: '', remarks: '' });
  const [reviewLoading, setReviewLoading] = useState(false);

  // Dedicated Action A: Approve Non-Civic Modal
  const [approveModalComplaint, setApproveModalComplaint] = useState(null);
  const [approveForm, setApproveForm] = useState({ category: 'Potholes and Road Cracks', remarks: '', departmentId: '' });
  const [approveLoading, setApproveLoading] = useState(false);

  // Dedicated Action B: Reject Non-Civic Modal
  const [rejectModalComplaint, setRejectModalComplaint] = useState(null);
  const [rejectForm, setRejectForm] = useState({ rejectionReason: '', remarks: '' });
  const [rejectLoading, setRejectLoading] = useState(false);

  // Timeline modal state
  const [historyModalComplaint, setHistoryModalComplaint] = useState(null);

  // Staff creation modals
  const [showStaffModal, setShowStaffModal] = useState(false); // 'SUPERVISOR' | 'WORKER' | false
  const [staffForm, setStaffForm] = useState({ name: '', mobileNumber: '', departmentId: '' });
  const [staffLoading, setStaffLoading] = useState(false);

  // Department creation modal
  const [showDeptModal, setShowDeptModal] = useState(false);
  const [showDeptListModal, setShowDeptListModal] = useState(false);
  const [deptForm, setDeptForm] = useState({ name: '', description: '' });
  const [deptLoading, setDeptLoading] = useState(false);

  useEffect(() => {
    loadData();
  }, []);

  const loadData = async () => {
    setLoading(true);
    try {
      const [compData, deptData] = await Promise.all([
        getAdminComplaints(idToken),
        getDepartments(idToken),
      ]);
      setComplaints(compData || []);
      setDepartments(Array.isArray(deptData) ? deptData : []);
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setLoading(false);
    }
  };

  const handleForward = async (complaintId) => {
    const deptId = selectedDepts[complaintId];
    if (!deptId) {
      setMessage({ type: 'error', text: 'Please select a department first.' });
      return;
    }

    setActionLoading((prev) => ({ ...prev, [complaintId]: true }));
    setMessage(null);

    try {
      const updated = await forwardComplaint(idToken, complaintId, Number(deptId));
      setMessage({ type: 'success', text: `Complaint ${updated.complaintNumber} forwarded to ${updated.departmentName} successfully!` });
      loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setActionLoading((prev) => ({ ...prev, [complaintId]: false }));
    }
  };

  const openReviewModal = (complaint) => {
    setReviewModalComplaint(complaint);
    setReviewForm({
      departmentId: complaint.departmentId || '',
      supervisorMobile: complaint.supervisorMobile || '',
      category: complaint.predictedCategory || '',
      remarks: '',
    });
  };

  const handleManualAssignSubmit = async (e) => {
    e.preventDefault();
    if (!reviewForm.departmentId) {
      setMessage({ type: 'error', text: 'Please select a responsible department.' });
      return;
    }

    setReviewLoading(true);
    setMessage(null);

    try {
      const updated = await manualAssignComplaint(idToken, reviewModalComplaint.id, {
        departmentId: Number(reviewForm.departmentId),
        supervisorMobile: reviewForm.supervisorMobile?.trim() || null,
        category: reviewForm.category?.trim() || null,
        remarks: reviewForm.remarks?.trim() || null,
      });
      setMessage({
        type: 'success',
        text: `Complaint ${updated.complaintNumber} successfully assigned to ${updated.departmentName}!`,
      });
      setReviewModalComplaint(null);
      await loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setReviewLoading(false);
    }
  };

  // Open Action A: Approve Modal
  const openApproveModal = (complaint) => {
    const initialCategory = 'Potholes and Road Cracks';
    const deptName = CATEGORY_TO_DEPARTMENT[initialCategory];
    const matchedDept = departments.find(d => d.name.toLowerCase() === deptName?.toLowerCase());

    setApproveModalComplaint(complaint);
    setApproveForm({
      category: initialCategory,
      remarks: '',
      departmentId: matchedDept ? String(matchedDept.id) : '',
    });
  };

  const handleCategoryChangeInApprove = (selectedCategory) => {
    const deptName = CATEGORY_TO_DEPARTMENT[selectedCategory];
    const matchedDept = departments.find(d => d.name.toLowerCase() === deptName?.toLowerCase());
    setApproveForm(prev => ({
      ...prev,
      category: selectedCategory,
      departmentId: matchedDept ? String(matchedDept.id) : prev.departmentId
    }));
  };

  const handleApproveSubmit = async (e) => {
    e.preventDefault();
    if (!approveForm.category) {
      setMessage({ type: 'error', text: 'Please select a civic category.' });
      return;
    }

    setApproveLoading(true);
    setMessage(null);

    try {
      const updated = await approveComplaint(idToken, approveModalComplaint.id, {
        category: approveForm.category.trim(),
        remarks: approveForm.remarks?.trim() || null,
        departmentId: approveForm.departmentId ? Number(approveForm.departmentId) : null,
      });
      setMessage({
        type: 'success',
        text: `✅ Complaint ${updated.complaintNumber} approved as "${updated.predictedCategory}" and forwarded to ${updated.departmentName}!`,
      });
      setApproveModalComplaint(null);
      await loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setApproveLoading(false);
    }
  };

  // Open Action B: Reject Modal
  const openRejectModal = (complaint) => {
    setRejectModalComplaint(complaint);
    setRejectForm({
      rejectionReason: '',
      remarks: '',
    });
  };

  const handleRejectSubmit = async (e) => {
    e.preventDefault();
    if (!rejectForm.rejectionReason.trim()) {
      setMessage({ type: 'error', text: 'Please enter a rejection reason.' });
      return;
    }

    setRejectLoading(true);
    setMessage(null);

    try {
      const updated = await rejectComplaint(idToken, rejectModalComplaint.id, {
        rejectionReason: rejectForm.rejectionReason.trim(),
        remarks: rejectForm.remarks?.trim() || null,
      });
      setMessage({
        type: 'success',
        text: `Complaint ${updated.complaintNumber} marked as REJECTED (${updated.adminRejectionReason}).`,
      });
      setRejectModalComplaint(null);
      await loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setRejectLoading(false);
    }
  };

  const handleCreateStaff = async (e) => {
    e.preventDefault();
    if (!staffForm.mobileNumber || staffForm.mobileNumber.length !== 10) {
      setMessage({ type: 'error', text: 'Please enter a valid 10-digit mobile number.' });
      return;
    }
    if (!staffForm.departmentId) {
      setMessage({ type: 'error', text: 'Please select a department.' });
      return;
    }

    setStaffLoading(true);
    setMessage(null);

    try {
      const payload = {
        name: staffForm.name.trim() || null,
        mobileNumber: staffForm.mobileNumber.trim(),
        role: showStaffModal, // 'SUPERVISOR' or 'WORKER'
        departmentId: Number(staffForm.departmentId),
      };

      await createStaffUser(idToken, payload);
      setMessage({ type: 'success', text: `Successfully created ${showStaffModal}: ${staffForm.name || staffForm.mobileNumber}` });
      setShowStaffModal(false);
      setStaffForm({ name: '', mobileNumber: '', departmentId: '' });
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setStaffLoading(false);
    }
  };

  const handleCreateDepartment = async (e) => {
    e.preventDefault();
    const trimmedName = deptForm.name.trim();
    if (!trimmedName) {
      setMessage({ type: 'error', text: 'Department name is required.' });
      return;
    }

    setDeptLoading(true);
    setMessage(null);

    try {
      const newDept = await createDepartment(idToken, {
        name: trimmedName,
        description: deptForm.description?.trim() || null,
      });
      setMessage({
        type: 'success',
        text: `Department "${newDept.name}" created successfully in PostgreSQL (ID: ${newDept.id})!`,
      });
      setShowDeptModal(false);
      setDeptForm({ name: '', description: '' });
      await loadData();
    } catch (err) {
      setMessage({ type: 'error', text: err.message });
    } finally {
      setDeptLoading(false);
    }
  };

  // Compute Task Summaries
  const totalCount = complaints.length;
  const manualReviewCount = complaints.filter(c => c.requiresManualReview).length;
  const pendingLowConfidenceCount = complaints.filter(c =>
    (c.status === 'PENDING_ADMIN_REVIEW' || c.reviewStatus === 'PENDING_ADMIN_REVIEW' || Boolean(c.requiresManualReview) || (c.confidenceScore != null && c.confidenceScore < 0.50)) &&
    c.status !== 'FORWARDED' && c.status !== 'REJECTED'
  ).length;
  const submittedCount = complaints.filter(c => c.status === 'SUBMITTED').length;
  const forwardedCount = complaints.filter(c => c.status === 'FORWARDED').length;
  const assignedCount = complaints.filter(c => c.status === 'ASSIGNED').length;
  const inProgressCount = complaints.filter(c => c.status === 'IN_PROGRESS').length;
  const resolvedCount = complaints.filter(c => c.status === 'RESOLVED').length;

  const onTimeCount = complaints.filter(c => c.slaStatus === 'ON_TIME').length;
  const dueSoonCount = complaints.filter(c => c.slaStatus === 'DUE_SOON').length;
  const overdueCount = complaints.filter(c => c.slaStatus === 'OVERDUE').length;
  const resolvedWithinSlaCount = complaints.filter(c => c.resolvedWithinSla === true).length;
  const resolvedOverdueCount = complaints.filter(c => c.resolvedWithinSla === false).length;

  // Filtered complaints for All Grievances
  const filteredComplaints = complaints.filter(c => {
    if (reviewFilter === 'REVIEW_ONLY' && !c.requiresManualReview) return false;
    if (statusFilter !== 'ALL' && c.status !== statusFilter) return false;
    if (slaFilter !== 'ALL' && c.slaStatus !== slaFilter) return false;
    if (deptFilter !== 'ALL' && String(c.departmentId) !== String(deptFilter)) return false;
    if (issueTypeFilter !== 'ALL' && c.issueType !== issueTypeFilter) return false;
    return true;
  });

  // Low-confidence complaints list for Admin Review section (< 50% threshold)
  const lowConfidenceComplaints = complaints.filter(c => {
    const isUnderReview = c.status === 'PENDING_ADMIN_REVIEW' ||
      c.reviewStatus === 'PENDING_ADMIN_REVIEW' ||
      Boolean(c.requiresManualReview) ||
      (c.confidenceScore != null && c.confidenceScore < 0.50) ||
      c.reviewStatus === 'ADMIN_APPROVED' ||
      c.reviewStatus === 'ADMIN_REJECTED';

    if (!isUnderReview) return false;
    if (reviewTabFilter === 'PENDING') {
      return (c.status === 'PENDING_ADMIN_REVIEW' || c.reviewStatus === 'PENDING_ADMIN_REVIEW' || Boolean(c.requiresManualReview) || (c.confidenceScore != null && c.confidenceScore < 0.50)) &&
        c.status !== 'FORWARDED' && c.status !== 'REJECTED';
    }
    if (reviewTabFilter === 'APPROVED') {
      return c.reviewStatus === 'ADMIN_APPROVED' || c.assignmentMethod === 'ADMIN_MANUAL_APPROVAL' || c.classificationSource === 'ADMIN_VERIFIED';
    }
    if (reviewTabFilter === 'REJECTED') {
      return c.status === 'REJECTED' || c.reviewStatus === 'ADMIN_REJECTED';
    }
    return true;
  });

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

  const getStepIndicator = (status) => {
    const steps = ['SUBMITTED', 'FORWARDED', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED'];
    const currentIndex = steps.indexOf(status);

    return (
      <div style={{ display: 'flex', alignItems: 'center', gap: '4px', margin: '12px 0' }}>
        {steps.map((st, idx) => {
          const isPassed = currentIndex >= idx;
          const isCurrent = currentIndex === idx;
          return (
            <React.Fragment key={st}>
              <div style={{
                flex: 1,
                textAlign: 'center',
                padding: '4px 2px',
                borderRadius: '4px',
                fontSize: '9px',
                fontWeight: isCurrent ? '800' : '600',
                backgroundColor: isCurrent ? '#0284C7' : isPassed ? '#1E293B' : '#0F172A',
                color: isCurrent ? '#FFFFFF' : isPassed ? '#94A3B8' : '#475569',
                border: isCurrent ? '1px solid #38BDF8' : '1px solid #334155'
              }}>
                {st.replace('_', ' ')}
              </div>
              {idx < steps.length - 1 && <span style={{ color: '#475569', fontSize: '10px' }}>➔</span>}
            </React.Fragment>
          );
        })}
      </div>
    );
  };

  return (
    <div style={{ padding: '24px', maxWidth: '1400px', margin: '0 auto' }}>
      {/* Top Header & Staff Creation Buttons */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '20px', flexWrap: 'wrap', gap: '12px' }}>
        <div>
          <h2 style={{ fontSize: '24px', fontWeight: '800', color: '#38BDF8' }}>Admin Civic Intelligence Center</h2>
          <p style={{ fontSize: '13px', color: '#94A3B8' }}>Two-Stage ML Cascaded Pipeline Triage, Department Routing, and Lifecycle SLA Compliance</p>
        </div>
        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
          <button onClick={() => setShowDeptListModal(true)} className="btn-primary" style={{ backgroundColor: '#0284C7' }}>
            🏛️ View Departments ({departments.length})
          </button>
          <button onClick={() => setShowDeptModal(true)} className="btn-primary" style={{ backgroundColor: '#059669' }}>
            + Create Department
          </button>
          <button onClick={() => setShowStaffModal('SUPERVISOR')} className="btn-primary" style={{ backgroundColor: '#7C3AED' }}>
            + Create Supervisor
          </button>
          <button onClick={() => setShowStaffModal('WORKER')} className="btn-primary" style={{ backgroundColor: '#2563EB' }}>
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

      {/* Primary Section Switcher Tabs */}
      <div style={{ display: 'flex', gap: '12px', marginBottom: '20px', borderBottom: '1px solid #334155', paddingBottom: '12px' }}>
        <button
          onClick={() => setActiveSection('ALL_GRIEVANCES')}
          style={{
            padding: '10px 20px',
            borderRadius: '8px',
            border: activeSection === 'ALL_GRIEVANCES' ? '1px solid #38BDF8' : '1px solid #334155',
            backgroundColor: activeSection === 'ALL_GRIEVANCES' ? '#0369A1' : '#0F172A',
            color: activeSection === 'ALL_GRIEVANCES' ? '#FFFFFF' : '#94A3B8',
            fontWeight: '700',
            fontSize: '14px',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
          }}
        >
          📋 All Civic Grievances ({complaints.length})
        </button>

        <button
          onClick={() => setActiveSection('NON_CIVIC_REVIEW')}
          style={{
            padding: '10px 20px',
            borderRadius: '8px',
            border: activeSection === 'NON_CIVIC_REVIEW' ? '1px solid #F59E0B' : '1px solid #334155',
            backgroundColor: activeSection === 'NON_CIVIC_REVIEW' ? '#78350F' : '#0F172A',
            color: activeSection === 'NON_CIVIC_REVIEW' ? '#FDE68A' : '#FBBF24',
            fontWeight: '700',
            fontSize: '14px',
            cursor: 'pointer',
            display: 'flex',
            alignItems: 'center',
            gap: '8px',
          }}
        >
          🛡️ Low-Confidence Complaints - Admin Review Queue
          {pendingLowConfidenceCount > 0 && (
            <span style={{
              backgroundColor: '#EF4444',
              color: '#FFFFFF',
              borderRadius: '12px',
              padding: '2px 8px',
              fontSize: '11px',
              fontWeight: '800'
            }}>
              {pendingLowConfidenceCount} Pending
            </span>
          )}
        </button>
      </div>

      {activeSection === 'ALL_GRIEVANCES' ? (
        <>
          {/* Task Summary & SLA Metric Counters */}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: '12px', marginBottom: '20px' }}>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '11px', color: '#94A3B8', fontWeight: '600' }}>TOTAL</div>
              <div style={{ fontSize: '22px', fontWeight: '800', color: '#F8FAFC' }}>{totalCount}</div>
            </div>
            <div
              className="glass-card"
              style={{
                padding: '12px',
                textAlign: 'center',
                borderColor: manualReviewCount > 0 ? '#F59E0B' : '#475569',
                backgroundColor: manualReviewCount > 0 ? 'rgba(245, 158, 11, 0.12)' : undefined,
                cursor: 'pointer'
              }}
              onClick={() => setReviewFilter(reviewFilter === 'REVIEW_ONLY' ? 'ALL' : 'REVIEW_ONLY')}
              title="Click to toggle filter for complaints requiring review"
            >
              <div style={{ fontSize: '11px', color: '#FBBF24', fontWeight: '700' }}>⚠️ REQUIRES REVIEW</div>
              <div style={{ fontSize: '22px', fontWeight: '800', color: '#FBBF24' }}>{manualReviewCount}</div>
            </div>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#F59E0B66' }}>
              <div style={{ fontSize: '11px', color: '#F59E0B', fontWeight: '600' }}>SUBMITTED</div>
              <div style={{ fontSize: '22px', fontWeight: '800', color: '#FBBF24' }}>{submittedCount}</div>
            </div>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#3B82F666' }}>
              <div style={{ fontSize: '11px', color: '#60A5FA', fontWeight: '600' }}>FORWARDED</div>
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
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#10B98166' }}>
              <div style={{ fontSize: '11px', color: '#34D399', fontWeight: '600' }}>ON TIME</div>
              <div style={{ fontSize: '22px', fontWeight: '800', color: '#34D399' }}>{onTimeCount}</div>
            </div>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#F59E0B66' }}>
              <div style={{ fontSize: '11px', color: '#FBBF24', fontWeight: '600' }}>DUE SOON</div>
              <div style={{ fontSize: '22px', fontWeight: '800', color: '#FBBF24' }}>{dueSoonCount}</div>
            </div>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center', borderColor: '#EF444466' }}>
              <div style={{ fontSize: '11px', color: '#F87171', fontWeight: '600' }}>OVERDUE</div>
              <div style={{ fontSize: '22px', fontWeight: '800', color: '#F87171' }}>{overdueCount}</div>
            </div>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#94A3B8', fontWeight: '600' }}>RESOLVED (IN SLA)</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#34D399' }}>{resolvedWithinSlaCount}</div>
            </div>
            <div className="glass-card" style={{ padding: '12px', textAlign: 'center' }}>
              <div style={{ fontSize: '10px', color: '#94A3B8', fontWeight: '600' }}>RESOLVED (LATE)</div>
              <div style={{ fontSize: '20px', fontWeight: '800', color: '#F87171' }}>{resolvedOverdueCount}</div>
            </div>
          </div>

          {/* Multi-Dimensional Filter Bar */}
          <div className="glass-card" style={{ padding: '16px', marginBottom: '20px', display: 'flex', flexWrap: 'wrap', gap: '14px', alignItems: 'center' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8' }}>Manual Review:</label>
              <select className="input-field" style={{ padding: '6px 10px', fontSize: '12px', borderColor: reviewFilter === 'REVIEW_ONLY' ? '#F59E0B' : undefined }} value={reviewFilter} onChange={(e) => setReviewFilter(e.target.value)}>
                <option value="ALL">All Complaints</option>
                <option value="REVIEW_ONLY">⚠️ Review Needed Only ({manualReviewCount})</option>
              </select>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8' }}>Filter Status:</label>
              <select className="input-field" style={{ padding: '6px 10px', fontSize: '12px' }} value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
                <option value="ALL">All Statuses</option>
                <option value="SUBMITTED">SUBMITTED</option>
                <option value="PENDING_ADMIN_REVIEW">PENDING_ADMIN_REVIEW</option>
                <option value="FORWARDED">FORWARDED</option>
                <option value="ASSIGNED">ASSIGNED</option>
                <option value="IN_PROGRESS">IN_PROGRESS</option>
                <option value="RESOLVED">RESOLVED</option>
                <option value="REJECTED">REJECTED</option>
              </select>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8' }}>Filter SLA:</label>
              <select className="input-field" style={{ padding: '6px 10px', fontSize: '12px' }} value={slaFilter} onChange={(e) => setSlaFilter(e.target.value)}>
                <option value="ALL">All SLA Statuses</option>
                <option value="ON_TIME">ON_TIME</option>
                <option value="DUE_SOON">DUE_SOON</option>
                <option value="OVERDUE">OVERDUE</option>
                <option value="RESOLVED">RESOLVED</option>
              </select>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8' }}>Filter Department:</label>
              <select className="input-field" style={{ padding: '6px 10px', fontSize: '12px' }} value={deptFilter} onChange={(e) => setDeptFilter(e.target.value)}>
                <option value="ALL">All Departments</option>
                {departments.map(d => (
                  <option key={d.id} value={d.id}>{d.name} (ID: {d.id})</option>
                ))}
              </select>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
              <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8' }}>Filter Issue Type:</label>
              <select className="input-field" style={{ padding: '6px 10px', fontSize: '12px' }} value={issueTypeFilter} onChange={(e) => setIssueTypeFilter(e.target.value)}>
                <option value="ALL">All Issue Types</option>
                <option value="POTHOLE">POTHOLE</option>
                <option value="GARBAGE">GARBAGE</option>
                <option value="WATER_LEAK">WATER_LEAK</option>
                <option value="DRAINAGE_ISSUE">DRAINAGE_ISSUE</option>
                <option value="STREET_LIGHT_DAMAGE">STREET_LIGHT_DAMAGE</option>
                <option value="INFRASTRUCTURE_DAMAGE">INFRASTRUCTURE_DAMAGE</option>
                <option value="NONE">NONE</option>
              </select>
            </div>

            <div style={{ marginLeft: 'auto', alignSelf: 'flex-end' }}>
              <span style={{ fontSize: '12px', color: '#94A3B8' }}>
                Showing <strong>{filteredComplaints.length}</strong> of {complaints.length} complaints
              </span>
            </div>
          </div>

          {/* Complaints Grid */}
          {loading ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#94A3B8' }}>Loading Admin Complaints & Lifecycle Data...</div>
          ) : filteredComplaints.length === 0 ? (
            <div className="glass-card" style={{ padding: '40px', textAlign: 'center', color: '#94A3B8' }}>
              No complaints match current filters.
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(400px, 1fr))', gap: '20px' }}>
              {filteredComplaints.map((item) => {
                const forwardedAt = item.statusHistory?.find(h => h.newStatus === 'FORWARDED')?.changedAt;
                const assignedAt = item.statusHistory?.find(h => h.newStatus === 'ASSIGNED')?.changedAt;
                const inProgressAt = item.statusHistory?.find(h => h.newStatus === 'IN_PROGRESS')?.changedAt;
                const resolvedAt = item.resolvedAt || item.statusHistory?.find(h => h.newStatus === 'RESOLVED')?.changedAt;

                const isLowConfidence = item.status === 'PENDING_ADMIN_REVIEW' || item.reviewStatus === 'PENDING_ADMIN_REVIEW' || Boolean(item.requiresManualReview) || (item.confidenceScore != null && item.confidenceScore < 0.50);

                return (
                  <div key={item.id} className="glass-card" style={{
                    padding: '18px',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'space-between',
                    border: isLowConfidence ? '1px solid rgba(245, 158, 11, 0.4)' : undefined,
                  }}>
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                        <span style={{ fontSize: '17px', fontWeight: '800', color: '#F8FAFC' }}>{item.complaintNumber}</span>
                        <div style={{ display: 'flex', gap: '6px', alignItems: 'center', flexWrap: 'wrap' }}>
                          {isLowConfidence && (
                            <span style={{ backgroundColor: '#78350F', border: '1px solid #F59E0B', color: '#FDE68A', fontSize: '11px', fontWeight: '700', padding: '2px 8px', borderRadius: '12px' }}>
                              🛡️ Low Confidence (&lt; 50%)
                            </span>
                          )}
                          <span className={`badge badge-${item.status.toLowerCase()}`}>{item.status}</span>
                          {getSlaBadge(item.slaStatus, item.remainingMinutes)}
                          <span style={{ backgroundColor: 'rgba(245, 158, 11, 0.15)', border: '1px solid #F59E0B', color: '#FBBF24', fontSize: '11px', fontWeight: '700', padding: '2px 8px', borderRadius: '12px' }}>
                            👥 {item.joinCount || 0} Joined
                          </span>
                        </div>
                      </div>

                      {/* Lifecycle Progression Indicator */}
                      {getStepIndicator(item.status)}

                      <div style={{ display: 'flex', gap: '12px', marginBottom: '14px' }}>
                        {/* Complaint photo */}
                        <div style={{ flex: 1, height: '140px', borderRadius: '6px', overflow: 'hidden', backgroundColor: '#0F172A' }}>
                          <img
                            src={item.imageUrl.startsWith('/') ? `http://localhost:8080${item.imageUrl}` : item.imageUrl}
                            alt="Issue"
                            style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                          />
                        </div>
                        {/* Proof image if resolved */}
                        {item.proofImageUrl && (
                          <div style={{ flex: 1, height: '140px', borderRadius: '6px', overflow: 'hidden', backgroundColor: '#0F172A', border: '1px solid #10B981' }}>
                            <img
                              src={item.proofImageUrl.startsWith('/') ? `http://localhost:8080${item.proofImageUrl}` : item.proofImageUrl}
                              alt="Proof"
                              style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                            />
                          </div>
                        )}
                      </div>

                      {/* AI Prediction & Routing Metadata Block */}
                      <div style={{ backgroundColor: '#0B132B', border: '1px solid #1E293B', padding: '8px 10px', borderRadius: '6px', fontSize: '11px', color: '#CBD5E1', marginBottom: '12px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                          <div>
                            <strong>AI Triage: </strong>
                            <span style={{ color: '#38BDF8', fontWeight: '700' }}>
                              {item.predictedCategory || item.issueType || 'Not Analyzed'}
                            </span>
                          </div>
                          <div>
                            <strong>Confidence: </strong>
                            <span style={{ color: (item.confidenceScore || 0) > 0.5 ? '#34D399' : '#F87171', fontWeight: '800' }}>
                              {item.confidenceScore != null ? (item.confidenceScore * 100).toFixed(1) + '%' : 'N/A'}
                            </span>
                          </div>
                        </div>
                        <div style={{ fontSize: '10px', color: '#94A3B8' }}>
                          Method: <span style={{ color: item.requiresManualReview ? '#FBBF24' : '#38BDF8' }}>{item.assignmentMethod || 'N/A'}</span>
                          {item.modelVersion && ` | ${item.modelVersion}`}
                        </div>
                        {item.adminReviewReason && (
                          <div style={{ color: '#F87171', backgroundColor: 'rgba(239, 68, 68, 0.1)', border: '1px solid rgba(239, 68, 68, 0.3)', padding: '3px 6px', borderRadius: '4px', marginTop: '4px' }}>
                            ⚠️ <strong>Reason:</strong> {item.adminReviewReason}
                          </div>
                        )}
                        {item.adminRejectionReason && (
                          <div style={{ color: '#F87171', backgroundColor: 'rgba(239, 68, 68, 0.1)', border: '1px solid rgba(239, 68, 68, 0.3)', padding: '3px 6px', borderRadius: '4px', marginTop: '4px' }}>
                            ❌ <strong>Rejection Reason:</strong> {item.adminRejectionReason}
                          </div>
                        )}
                      </div>

                      {/* Metadata Details */}
                      <div style={{ fontSize: '12px', color: '#CBD5E1', display: 'flex', flexDirection: 'column', gap: '5px', marginBottom: '14px' }}>
                        <div><strong>Issue:</strong> <span style={{ color: '#38BDF8' }}>{item.issueType}</span> (SLA: {item.slaHours}h)</div>
                        <div><strong>Citizen:</strong> {item.citizenMobile} {item.citizenName ? `(${item.citizenName})` : ''}</div>
                        <div><strong>Department:</strong> {item.departmentName || <span style={{ color: '#F59E0B' }}>Not Assigned</span>}</div>
                        <div><strong>Supervisor:</strong> {item.supervisorName ? `${item.supervisorName} (${item.supervisorMobile})` : <span style={{ color: '#94A3B8' }}>None</span>}</div>
                        <div><strong>Worker:</strong> {item.assignedWorkerName ? `${item.assignedWorkerName} (${item.assignedWorkerMobile})` : <span style={{ color: '#94A3B8' }}>None</span>}</div>
                        <div>
                          <strong>Location:</strong> 📍 Lat {item.latitude}, Lng {item.longitude}{' '}
                          <a
                            href={`https://www.google.com/maps/search/?api=1&query=${item.latitude},${item.longitude}`}
                            target="_blank"
                            rel="noreferrer"
                            style={{ color: '#38BDF8', textDecoration: 'underline', marginLeft: '6px' }}
                          >
                            Map ↗
                          </a>
                        </div>
                        <div><strong>Citizens Joined:</strong> <span style={{ color: '#FBBF24', fontWeight: '700' }}>👥 {item.joinCount || 0} citizens participating</span></div>
                        {item.slaDeadline && <div><strong>Deadline:</strong> {new Date(item.slaDeadline).toLocaleString()}</div>}
                      </div>

                      {/* Stage Timestamps */}
                      <div style={{ backgroundColor: '#0F172A', padding: '8px 10px', borderRadius: '6px', fontSize: '11px', color: '#94A3B8', display: 'flex', flexDirection: 'column', gap: '3px', marginBottom: '12px' }}>
                        <div>🕒 <strong>Created:</strong> {new Date(item.createdAt).toLocaleString()}</div>
                        {forwardedAt && <div>📨 <strong>Forwarded:</strong> {new Date(forwardedAt).toLocaleString()}</div>}
                        {assignedAt && <div>👷 <strong>Assigned:</strong> {new Date(assignedAt).toLocaleString()}</div>}
                        {inProgressAt && <div>⚙️ <strong>Started:</strong> {new Date(inProgressAt).toLocaleString()}</div>}
                        {resolvedAt && <div>✅ <strong>Resolved:</strong> {new Date(resolvedAt).toLocaleString()} {item.resolvedWithinSla === true ? '(In SLA)' : item.resolvedWithinSla === false ? '(Overdue)' : ''}</div>}
                      </div>
                    </div>

                    <div>
                      {isLowConfidence && (item.status === 'PENDING_ADMIN_REVIEW' || item.status === 'SUBMITTED') ? (
                        <div style={{ display: 'flex', gap: '8px', marginBottom: '8px' }}>
                          <button
                            onClick={() => openApproveModal(item)}
                            style={{
                              flex: 1,
                              padding: '8px',
                              backgroundColor: '#059669',
                              border: 'none',
                              borderRadius: '6px',
                              color: '#FFFFFF',
                              fontWeight: '700',
                              fontSize: '12px',
                              cursor: 'pointer',
                            }}
                          >
                            ✅ Approve & Assign
                          </button>
                          <button
                            onClick={() => openRejectModal(item)}
                            style={{
                              flex: 1,
                              padding: '8px',
                              backgroundColor: '#DC2626',
                              border: 'none',
                              borderRadius: '6px',
                              color: '#FFFFFF',
                              fontWeight: '700',
                              fontSize: '12px',
                              cursor: 'pointer',
                            }}
                          >
                            ❌ Reject
                          </button>
                        </div>
                      ) : (
                        <button
                          onClick={() => openReviewModal(item)}
                          style={{
                            width: '100%',
                            padding: '8px',
                            backgroundColor: item.requiresManualReview ? '#D97706' : '#0284C7',
                            border: 'none',
                            borderRadius: '6px',
                            color: '#FFFFFF',
                            fontWeight: '700',
                            fontSize: '12px',
                            cursor: 'pointer',
                            marginBottom: '8px'
                          }}
                        >
                          🛠️ {item.requiresManualReview ? 'Manual Review & Assign' : 'Reassign / Edit Department'}
                        </button>
                      )}

                      <button
                        onClick={() => setHistoryModalComplaint(item)}
                        style={{ width: '100%', padding: '6px', background: 'none', border: '1px solid #334155', borderRadius: '6px', color: '#38BDF8', fontSize: '12px', cursor: 'pointer', marginBottom: '10px' }}
                      >
                        📜 View Complete Timeline ({item.statusHistory?.length || 0} events)
                      </button>

                      {item.status === 'SUBMITTED' && !isLowConfidence && (
                        <div style={{ borderTop: '1px solid #334155', paddingTop: '10px' }}>
                          <label style={{ fontSize: '11px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                            Forward to Department:
                          </label>
                          <div style={{ display: 'flex', gap: '8px' }}>
                            <select
                              className="input-field"
                              style={{ flex: 1, fontSize: '12px' }}
                              value={selectedDepts[item.id] || ''}
                              onChange={(e) => setSelectedDepts({ ...selectedDepts, [item.id]: e.target.value })}
                            >
                              <option value="">{departments.length === 0 ? '-- No departments exist --' : '-- Select Department --'}</option>
                              {departments.map((d) => (
                                <option key={d.id} value={d.id}>{d.name} (ID: {d.id})</option>
                              ))}
                            </select>
                            <button
                              className="btn-primary"
                              disabled={actionLoading[item.id]}
                              onClick={() => handleForward(item.id)}
                              style={{ padding: '6px 12px', fontSize: '12px' }}
                            >
                              {actionLoading[item.id] ? '...' : 'Forward'}
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </>
      ) : (
        /* DEDICATED SECTION: LOW-CONFIDENCE COMPLAINTS - ADMIN REVIEW */
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px', flexWrap: 'wrap', gap: '12px' }}>
            <div>
              <h3 style={{ fontSize: '18px', fontWeight: '800', color: '#FBBF24' }}>
                🛡️ Low-Confidence Complaints - Admin Review Triage Queue
              </h3>
              <p style={{ fontSize: '13px', color: '#94A3B8' }}>
                Complaints with ML prediction confidence &lt; 50% or requiring manual triage. Admin must manually verify and assign to a department supervisor or reject.
              </p>
            </div>

            <div style={{ display: 'flex', gap: '8px' }}>
              <button
                onClick={() => setReviewTabFilter('PENDING')}
                style={{
                  padding: '6px 14px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  fontWeight: '700',
                  cursor: 'pointer',
                  border: reviewTabFilter === 'PENDING' ? '1px solid #F59E0B' : '1px solid #334155',
                  backgroundColor: reviewTabFilter === 'PENDING' ? '#78350F' : '#0F172A',
                  color: reviewTabFilter === 'PENDING' ? '#FDE68A' : '#94A3B8'
                }}
              >
                ⚠️ Pending Review ({pendingLowConfidenceCount})
              </button>
              <button
                onClick={() => setReviewTabFilter('APPROVED')}
                style={{
                  padding: '6px 14px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  fontWeight: '700',
                  cursor: 'pointer',
                  border: reviewTabFilter === 'APPROVED' ? '1px solid #10B981' : '1px solid #334155',
                  backgroundColor: reviewTabFilter === 'APPROVED' ? '#065F46' : '#0F172A',
                  color: reviewTabFilter === 'APPROVED' ? '#A7F3D0' : '#94A3B8'
                }}
              >
                ✅ Approved by Admin
              </button>
              <button
                onClick={() => setReviewTabFilter('REJECTED')}
                style={{
                  padding: '6px 14px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  fontWeight: '700',
                  cursor: 'pointer',
                  border: reviewTabFilter === 'REJECTED' ? '1px solid #EF4444' : '1px solid #334155',
                  backgroundColor: reviewTabFilter === 'REJECTED' ? '#7F1D1D' : '#0F172A',
                  color: reviewTabFilter === 'REJECTED' ? '#FCA5A5' : '#94A3B8'
                }}
              >
                ❌ Rejected
              </button>
              <button
                onClick={() => setReviewTabFilter('ALL')}
                style={{
                  padding: '6px 14px',
                  borderRadius: '6px',
                  fontSize: '12px',
                  fontWeight: '700',
                  cursor: 'pointer',
                  border: reviewTabFilter === 'ALL' ? '1px solid #38BDF8' : '1px solid #334155',
                  backgroundColor: reviewTabFilter === 'ALL' ? '#0369A1' : '#0F172A',
                  color: reviewTabFilter === 'ALL' ? '#BAE6FD' : '#94A3B8'
                }}
              >
                All Records
              </button>
            </div>
          </div>

          {loading ? (
            <div style={{ textAlign: 'center', padding: '40px', color: '#94A3B8' }}>Loading Low-Confidence Review Queue...</div>
          ) : lowConfidenceComplaints.length === 0 ? (
            <div className="glass-card" style={{ padding: '40px', textAlign: 'center', color: '#94A3B8' }}>
              No complaints in this review filter.
            </div>
          ) : (
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(420px, 1fr))', gap: '20px' }}>
              {lowConfidenceComplaints.map((item) => {
                const isPendingAction = item.status === 'PENDING_ADMIN_REVIEW' ||
                  item.reviewStatus === 'PENDING_ADMIN_REVIEW' ||
                  Boolean(item.requiresManualReview) ||
                  (item.confidenceScore != null && item.confidenceScore < 0.50 && item.status !== 'FORWARDED' && item.status !== 'REJECTED');

                return (
                  <div key={item.id} className="glass-card" style={{
                    padding: '20px',
                    display: 'flex',
                    flexDirection: 'column',
                    justifyContent: 'space-between',
                    border: '1px solid #F59E0B66',
                    backgroundColor: 'rgba(15, 23, 42, 0.85)'
                  }}>
                    <div>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '10px' }}>
                        <span style={{ fontSize: '18px', fontWeight: '800', color: '#F8FAFC' }}>{item.complaintNumber}</span>
                        <div style={{ display: 'flex', gap: '6px' }}>
                          <span style={{
                            backgroundColor: isPendingAction ? '#78350F' : item.status === 'REJECTED' ? '#7F1D1D' : '#065F46',
                            color: isPendingAction ? '#FDE68A' : item.status === 'REJECTED' ? '#FCA5A5' : '#A7F3D0',
                            fontSize: '11px',
                            fontWeight: '800',
                            padding: '3px 10px',
                            borderRadius: '12px'
                          }}>
                            {isPendingAction ? 'PENDING ADMIN REVIEW' : item.status}
                          </span>
                        </div>
                      </div>

                      {/* Image Preview */}
                      <div style={{ height: '180px', borderRadius: '8px', overflow: 'hidden', backgroundColor: '#0B132B', marginBottom: '14px', border: '1px solid #334155' }}>
                        <img
                          src={item.imageUrl.startsWith('/') ? `http://localhost:8080${item.imageUrl}` : item.imageUrl}
                          alt="Complaint Submission"
                          style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                        />
                      </div>

                      {/* 8-Class Model Diagnosis Box */}
                      <div style={{ backgroundColor: 'rgba(245, 158, 11, 0.1)', border: '1px solid #F59E0B66', padding: '10px 12px', borderRadius: '6px', marginBottom: '14px' }}>
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                          <span style={{ fontSize: '12px', fontWeight: '700', color: '#FBBF24' }}>
                            🎯 8-Class Model Diagnosis:
                          </span>
                          <span style={{ fontSize: '11px', fontWeight: '800', color: '#F87171' }}>
                            {item.confidenceScore != null && item.confidenceScore < 0.50 ? 'LOW CONFIDENCE (< 50%)' : (item.status === 'PENDING_ADMIN_REVIEW' ? 'PENDING REVIEW' : 'MANUAL TRIAGE')}
                          </span>
                        </div>
                        <div style={{ fontSize: '11px', color: '#CBD5E1' }}>
                          Predicted: <strong style={{ color: '#38BDF8' }}>{item.predictedCategory || 'Unknown'}</strong>
                          {' | '}Confidence: <strong style={{ color: item.confidenceScore != null && item.confidenceScore < 0.50 ? '#F87171' : '#34D399' }}>
                            {item.confidenceScore != null ? (item.confidenceScore * 100).toFixed(2) + '%' : 'N/A'}
                          </strong>
                          <span style={{ color: '#94A3B8', marginLeft: '6px' }}>(Threshold: 50.0%)</span>
                        </div>
                        {item.adminReviewReason && (
                          <div style={{ fontSize: '11px', color: '#FDE68A', marginTop: '6px' }}>
                            {item.adminReviewReason}
                          </div>
                        )}
                        {item.adminRejectionReason && (
                          <div style={{ fontSize: '11px', color: '#F87171', marginTop: '6px' }}>
                            ❌ Rejection Reason: <strong>{item.adminRejectionReason}</strong>
                          </div>
                        )}
                        {item.adminReviewRemarks && (
                          <div style={{ fontSize: '11px', color: '#94A3B8', marginTop: '4px', fontStyle: 'italic' }}>
                            Remarks: "{item.adminReviewRemarks}"
                          </div>
                        )}
                      </div>

                      {/* Detailed Metadata */}
                      <div style={{ fontSize: '12px', color: '#CBD5E1', display: 'flex', flexDirection: 'column', gap: '6px', marginBottom: '16px' }}>
                        <div><strong>Citizen Mobile:</strong> {item.citizenMobile} {item.citizenName ? `(${item.citizenName})` : ''}</div>
                        <div><strong>Submitted At:</strong> {new Date(item.createdAt).toLocaleString()}</div>
                        <div>
                          <strong>GPS Coordinates:</strong> 📍 {item.latitude}, {item.longitude}{' '}
                          <a
                            href={`https://www.google.com/maps/search/?api=1&query=${item.latitude},${item.longitude}`}
                            target="_blank"
                            rel="noreferrer"
                            style={{ color: '#38BDF8', textDecoration: 'underline', marginLeft: '6px' }}
                          >
                            Open in Google Maps ↗
                          </a>
                        </div>
                        <div>
                          <strong>Department:</strong> {item.departmentName ? (
                            <span style={{ color: '#34D399', fontWeight: '700' }}>{item.departmentName}</span>
                          ) : (
                            <span style={{ color: '#94A3B8', fontStyle: 'italic' }}>Unassigned (Pending Review)</span>
                          )}
                        </div>
                        {item.predictedCategory && (
                          <div>
                            <strong>Approved Category:</strong> <span style={{ color: '#38BDF8', fontWeight: '700' }}>{item.predictedCategory}</span>
                          </div>
                        )}
                      </div>
                    </div>

                    <div>
                      {isPendingAction ? (
                        <div style={{ display: 'flex', gap: '10px' }}>
                          <button
                            onClick={() => openApproveModal(item)}
                            style={{
                              flex: 1,
                              padding: '10px',
                              backgroundColor: '#059669',
                              border: 'none',
                              borderRadius: '6px',
                              color: '#FFFFFF',
                              fontWeight: '700',
                              fontSize: '13px',
                              cursor: 'pointer',
                            }}
                          >
                            ✅ Approve & Assign
                          </button>
                          <button
                            onClick={() => openRejectModal(item)}
                            style={{
                              flex: 1,
                              padding: '10px',
                              backgroundColor: '#DC2626',
                              border: 'none',
                              borderRadius: '6px',
                              color: '#FFFFFF',
                              fontWeight: '700',
                              fontSize: '13px',
                              cursor: 'pointer',
                            }}
                          >
                            ❌ Reject Complaint
                          </button>
                        </div>
                      ) : (
                        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                          <span style={{ fontSize: '11px', color: '#94A3B8' }}>
                            Action completed by: {item.adminReviewedBy || 'Admin'}
                          </span>
                          <button
                            onClick={() => setHistoryModalComplaint(item)}
                            style={{ padding: '6px 12px', background: 'none', border: '1px solid #334155', borderRadius: '6px', color: '#38BDF8', fontSize: '11px', cursor: 'pointer' }}
                          >
                            View Timeline
                          </button>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      )}

      {/* Action A: Approve Low-Confidence Complaint Modal */}
      {approveModalComplaint && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.85)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '580px', padding: '24px', maxHeight: '85vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '800', color: '#34D399' }}>
                ✅ Approve & Route Complaint: {approveModalComplaint.complaintNumber}
              </h3>
              <button
                onClick={() => setApproveModalComplaint(null)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            <div style={{ display: 'flex', gap: '14px', marginBottom: '16px', backgroundColor: '#0F172A', padding: '12px', borderRadius: '8px' }}>
              <div style={{ width: '120px', height: '100px', borderRadius: '6px', overflow: 'hidden', flexShrink: 0 }}>
                <img
                  src={approveModalComplaint.imageUrl.startsWith('/') ? `http://localhost:8080${approveModalComplaint.imageUrl}` : approveModalComplaint.imageUrl}
                  alt="Issue"
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                />
              </div>
              <div style={{ fontSize: '12px', color: '#CBD5E1', display: 'flex', flexDirection: 'column', gap: '4px' }}>
                <div><strong>Citizen:</strong> {approveModalComplaint.citizenMobile}</div>
                <div><strong>Predicted Category:</strong> <span style={{ color: '#38BDF8', fontWeight: '700' }}>{approveModalComplaint.predictedCategory || 'N/A'}</span></div>
                <div>
                  <strong>Prediction Confidence: </strong>
                  <span style={{ color: '#F59E0B', fontWeight: '700' }}>
                    {approveModalComplaint.confidenceScore != null ? (approveModalComplaint.confidenceScore * 100).toFixed(2) + '%' : 'N/A'}
                  </span>
                  <span style={{ color: '#94A3B8', marginLeft: '4px' }}>(&lt; 50% Threshold)</span>
                </div>
                <div><strong>Location:</strong> 📍 Lat {approveModalComplaint.latitude}, Lng {approveModalComplaint.longitude}</div>
              </div>
            </div>

            <form onSubmit={handleApproveSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ fontSize: '12px', fontWeight: '700', color: '#34D399', display: 'block', marginBottom: '6px' }}>
                  Select Canonical Civic Issue Category: *
                </label>
                <select
                  className="input-field"
                  style={{ width: '100%', borderColor: '#10B981' }}
                  value={approveForm.category}
                  onChange={(e) => handleCategoryChangeInApprove(e.target.value)}
                  required
                >
                  {CANONICAL_CATEGORIES.map((cat) => (
                    <option key={cat} value={cat}>{cat}</option>
                  ))}
                </select>
                <span style={{ fontSize: '11px', color: '#94A3B8', marginTop: '4px', display: 'block' }}>
                  Auto-routes to: <strong>{CATEGORY_TO_DEPARTMENT[approveForm.category] || 'General Department'}</strong>
                </span>
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Target Department (Preselected):
                </label>
                <select
                  className="input-field"
                  style={{ width: '100%' }}
                  value={approveForm.departmentId}
                  onChange={(e) => setApproveForm({ ...approveForm, departmentId: e.target.value })}
                >
                  <option value="">-- Auto-Pick from Category --</option>
                  {departments.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name} {d.description ? `(${d.description})` : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Admin Approval Remarks / Justification:
                </label>
                <textarea
                  className="input-field"
                  rows={3}
                  style={{ width: '100%', resize: 'vertical' }}
                  placeholder="e.g. Citizen captured genuine road damage; verified by Admin"
                  value={approveForm.remarks}
                  onChange={(e) => setApproveForm({ ...approveForm, remarks: e.target.value })}
                />
              </div>

              <div style={{ display: 'flex', gap: '10px', marginTop: '10px' }}>
                <button
                  type="button"
                  onClick={() => setApproveModalComplaint(null)}
                  style={{ flex: 1, padding: '10px', background: 'none', border: '1px solid #475569', borderRadius: '6px', color: '#94A3B8', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={approveLoading}
                  style={{ flex: 2, padding: '10px', backgroundColor: '#059669' }}
                >
                  {approveLoading ? 'Routing...' : 'Confirm Approval & Forward to Supervisor'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Action B: Reject Low-Confidence Complaint Modal */}
      {rejectModalComplaint && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.85)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '520px', padding: '24px', maxHeight: '85vh', overflowY: 'auto', border: '1px solid #EF4444' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '800', color: '#F87171' }}>
                ❌ Reject Complaint: {rejectModalComplaint.complaintNumber}
              </h3>
              <button
                onClick={() => setRejectModalComplaint(null)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleRejectSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ fontSize: '12px', fontWeight: '700', color: '#F87171', display: 'block', marginBottom: '6px' }}>
                  Rejection Reason: *
                </label>
                <input
                  type="text"
                  className="input-field"
                  style={{ width: '100%', borderColor: '#EF4444' }}
                  placeholder="e.g. Unclear image, spam, or invalid civic complaint"
                  value={rejectForm.rejectionReason}
                  onChange={(e) => setRejectForm({ ...rejectForm, rejectionReason: e.target.value })}
                  required
                />
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Additional Remarks (Optional):
                </label>
                <textarea
                  className="input-field"
                  rows={3}
                  style={{ width: '100%', resize: 'vertical' }}
                  placeholder="Additional context for audit trail..."
                  value={rejectForm.remarks}
                  onChange={(e) => setRejectForm({ ...rejectForm, remarks: e.target.value })}
                />
              </div>

              <div style={{ display: 'flex', gap: '10px', marginTop: '10px' }}>
                <button
                  type="button"
                  onClick={() => setRejectModalComplaint(null)}
                  style={{ flex: 1, padding: '10px', background: 'none', border: '1px solid #475569', borderRadius: '6px', color: '#94A3B8', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={rejectLoading}
                  style={{ flex: 2, padding: '10px', backgroundColor: '#DC2626' }}
                >
                  {rejectLoading ? 'Rejecting...' : 'Confirm Rejection'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Staff User Creation Modal */}
      {showStaffModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.75)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '440px', padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '700', color: '#F8FAFC' }}>
                Create New {showStaffModal === 'SUPERVISOR' ? 'Supervisor' : 'Worker'}
              </h3>
              <button
                onClick={() => setShowStaffModal(false)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleCreateStaff} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Full Name (Optional):
                </label>
                <input
                  type="text"
                  className="input-field"
                  style={{ width: '100%' }}
                  placeholder="e.g. Ramesh Kumar"
                  value={staffForm.name}
                  onChange={(e) => setStaffForm({ ...staffForm, name: e.target.value })}
                />
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Mobile Number (10 digits): *
                </label>
                <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <span style={{ color: '#94A3B8', fontSize: '13px' }}>+91</span>
                  <input
                    type="tel"
                    maxLength={10}
                    className="input-field"
                    style={{ flex: 1 }}
                    placeholder="9876543210"
                    value={staffForm.mobileNumber}
                    onChange={(e) => setStaffForm({ ...staffForm, mobileNumber: e.target.value.replace(/\D/g, '') })}
                    required
                  />
                </div>
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Department: *
                </label>
                <select
                  className="input-field"
                  style={{ width: '100%' }}
                  value={staffForm.departmentId}
                  onChange={(e) => setStaffForm({ ...staffForm, departmentId: e.target.value })}
                  required
                >
                  <option value="">-- Select Department --</option>
                  {departments.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name} {d.description ? `(${d.description})` : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div style={{ display: 'flex', gap: '10px', marginTop: '8px' }}>
                <button
                  type="button"
                  onClick={() => setShowStaffModal(false)}
                  style={{ flex: 1, padding: '8px', background: 'none', border: '1px solid #475569', borderRadius: '6px', color: '#94A3B8', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={staffLoading}
                  style={{ flex: 1, padding: '8px', backgroundColor: showStaffModal === 'SUPERVISOR' ? '#7C3AED' : '#2563EB' }}
                >
                  {staffLoading ? 'Creating...' : `Create ${showStaffModal}`}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Department Creation Modal */}
      {showDeptModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.75)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '440px', padding: '24px' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '700', color: '#F8FAFC' }}>Create Municipal Department</h3>
              <button
                onClick={() => setShowDeptModal(false)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            <form onSubmit={handleCreateDepartment} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Department Name: *
                </label>
                <input
                  type="text"
                  className="input-field"
                  style={{ width: '100%' }}
                  placeholder="e.g. Roads & Bridges Department"
                  value={deptForm.name}
                  onChange={(e) => setDeptForm({ ...deptForm, name: e.target.value })}
                  required
                />
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Description:
                </label>
                <textarea
                  className="input-field"
                  rows={3}
                  style={{ width: '100%', resize: 'vertical' }}
                  placeholder="Operational remit, e.g. Handles potholes and damaged concrete structures"
                  value={deptForm.description}
                  onChange={(e) => setDeptForm({ ...deptForm, description: e.target.value })}
                />
              </div>

              <div style={{ display: 'flex', gap: '10px', marginTop: '8px' }}>
                <button
                  type="button"
                  onClick={() => setShowDeptModal(false)}
                  style={{ flex: 1, padding: '8px', background: 'none', border: '1px solid #475569', borderRadius: '6px', color: '#94A3B8', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={deptLoading}
                  style={{ flex: 1, padding: '8px', backgroundColor: '#059669' }}
                >
                  {deptLoading ? 'Creating...' : 'Create Department'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Department List Modal */}
      {showDeptListModal && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.75)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '560px', padding: '24px', maxHeight: '80vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '700', color: '#F8FAFC' }}>
                Municipal Departments ({departments.length})
              </h3>
              <button
                onClick={() => setShowDeptListModal(false)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {departments.map((d) => (
                <div key={d.id} style={{ backgroundColor: '#0F172A', padding: '12px 16px', borderRadius: '8px', border: '1px solid #334155' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                    <span style={{ fontWeight: '700', color: '#F8FAFC', fontSize: '14px' }}>{d.name}</span>
                    <span style={{ fontSize: '11px', color: '#94A3B8', backgroundColor: '#1E293B', padding: '2px 8px', borderRadius: '4px' }}>
                      ID: {d.id}
                    </span>
                  </div>
                  {d.description && (
                    <div style={{ fontSize: '12px', color: '#94A3B8', marginTop: '4px' }}>
                      {d.description}
                    </div>
                  )}
                </div>
              ))}
            </div>

            <div style={{ marginTop: '16px', textAlign: 'right' }}>
              <button
                onClick={() => setShowDeptListModal(false)}
                className="btn-primary"
                style={{ backgroundColor: '#475569', padding: '8px 16px' }}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      )}

      {/* History Timeline Modal */}
      {historyModalComplaint && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.75)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '540px', padding: '24px', maxHeight: '80vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '700', color: '#F8FAFC' }}>
                Timeline: {historyModalComplaint.complaintNumber}
              </h3>
              <button
                onClick={() => setHistoryModalComplaint(null)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            {historyModalComplaint.statusHistory && historyModalComplaint.statusHistory.length > 0 ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {historyModalComplaint.statusHistory.map((h, i) => (
                  <div key={h.id || i} style={{ borderLeft: '2px solid #38BDF8', paddingLeft: '14px', position: 'relative' }}>
                    <div style={{ fontSize: '13px', fontWeight: '700', color: '#F8FAFC' }}>
                      {h.oldStatus ? `${h.oldStatus} ➔ ${h.newStatus}` : h.newStatus}
                    </div>
                    <div style={{ fontSize: '11px', color: '#94A3B8' }}>
                      Actor: {h.changedByName || h.changedByMobile || 'System'} | {new Date(h.changedAt).toLocaleString()}
                    </div>
                    {h.remarks && (
                      <div style={{ fontSize: '12px', color: '#CBD5E1', marginTop: '4px', fontStyle: 'italic' }}>
                        "{h.remarks}"
                      </div>
                    )}
                  </div>
                ))}
              </div>
            ) : (
              <div style={{ color: '#94A3B8', fontSize: '13px', textAlign: 'center', padding: '20px' }}>
                No status history entries available.
              </div>
            )}
          </div>
        </div>
      )}

      {/* Admin Manual Review & Reassignment Modal (General) */}
      {reviewModalComplaint && (
        <div style={{ position: 'fixed', top: 0, left: 0, right: 0, bottom: 0, backgroundColor: 'rgba(0,0,0,0.8)', display: 'flex', justifyContent: 'center', alignItems: 'center', zIndex: 1000, padding: '20px' }}>
          <div className="glass-card" style={{ width: '100%', maxWidth: '580px', padding: '24px', maxHeight: '85vh', overflowY: 'auto' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '16px' }}>
              <h3 style={{ fontSize: '18px', fontWeight: '800', color: '#F8FAFC' }}>
                🛠️ Admin Review & Assignment: {reviewModalComplaint.complaintNumber}
              </h3>
              <button
                onClick={() => setReviewModalComplaint(null)}
                style={{ background: 'none', border: 'none', color: '#94A3B8', fontSize: '18px', cursor: 'pointer' }}
              >
                ✕
              </button>
            </div>

            {/* Complaint image & AI analysis summary */}
            <div style={{ display: 'flex', gap: '14px', marginBottom: '16px', backgroundColor: '#0F172A', padding: '12px', borderRadius: '8px' }}>
              <div style={{ width: '120px', height: '100px', borderRadius: '6px', overflow: 'hidden', flexShrink: 0 }}>
                <img
                  src={reviewModalComplaint.imageUrl.startsWith('/') ? `http://localhost:8080${reviewModalComplaint.imageUrl}` : reviewModalComplaint.imageUrl}
                  alt="Issue"
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                />
              </div>
              <div style={{ fontSize: '12px', color: '#CBD5E1', display: 'flex', flexDirection: 'column', gap: '4px' }}>
                <div><strong>AI Predicted:</strong> <span style={{ color: '#38BDF8' }}>{reviewModalComplaint.predictedCategory || 'None'}</span></div>
                <div>
                  <strong>Confidence: </strong>
                  <span style={{ color: (reviewModalComplaint.confidenceScore || 0) > 0.5 ? '#34D399' : '#F87171', fontWeight: '700' }}>
                    {reviewModalComplaint.confidenceScore != null ? (reviewModalComplaint.confidenceScore * 100).toFixed(1) + '%' : 'N/A'}
                  </span>
                </div>
                <div><strong>Method:</strong> {reviewModalComplaint.assignmentMethod || 'N/A'}</div>
                {reviewModalComplaint.adminReviewReason && (
                  <div style={{ color: '#F87171', fontSize: '11px' }}>
                    ⚠️ {reviewModalComplaint.adminReviewReason}
                  </div>
                )}
              </div>
            </div>

            <form onSubmit={handleManualAssignSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '14px' }}>
              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Select Responsible Department: *
                </label>
                <select
                  className="input-field"
                  style={{ width: '100%' }}
                  value={reviewForm.departmentId}
                  onChange={(e) => setReviewForm({ ...reviewForm, departmentId: e.target.value })}
                  required
                >
                  <option value="">-- Select Department --</option>
                  {departments.map((d) => (
                    <option key={d.id} value={d.id}>
                      {d.name} {d.description ? `(${d.description})` : ''}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Complaint Category:
                </label>
                <select
                  className="input-field"
                  style={{ width: '100%' }}
                  value={reviewForm.category}
                  onChange={(e) => setReviewForm({ ...reviewForm, category: e.target.value })}
                >
                  <option value="">-- Keep Current Category --</option>
                  {CANONICAL_CATEGORIES.map((cat) => (
                    <option key={cat} value={cat}>{cat}</option>
                  ))}
                </select>
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Assign Specific Supervisor Mobile (Optional):
                </label>
                <input
                  type="text"
                  className="input-field"
                  style={{ width: '100%' }}
                  placeholder="Leave empty to auto-pick department supervisor"
                  value={reviewForm.supervisorMobile}
                  onChange={(e) => setReviewForm({ ...reviewForm, supervisorMobile: e.target.value })}
                />
              </div>

              <div>
                <label style={{ fontSize: '12px', fontWeight: '600', color: '#94A3B8', display: 'block', marginBottom: '4px' }}>
                  Review Remarks / Notes (Optional):
                </label>
                <textarea
                  className="input-field"
                  rows={3}
                  style={{ width: '100%', resize: 'vertical' }}
                  placeholder="Admin review notes regarding this routing decision"
                  value={reviewForm.remarks}
                  onChange={(e) => setReviewForm({ ...reviewForm, remarks: e.target.value })}
                />
              </div>

              <div style={{ display: 'flex', gap: '10px', marginTop: '10px' }}>
                <button
                  type="button"
                  onClick={() => setReviewModalComplaint(null)}
                  style={{ flex: 1, padding: '10px', background: 'none', border: '1px solid #475569', borderRadius: '6px', color: '#94A3B8', cursor: 'pointer' }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="btn-primary"
                  disabled={reviewLoading}
                  style={{ flex: 2, padding: '10px', backgroundColor: '#0284C7' }}
                >
                  {reviewLoading ? 'Assigning...' : 'Confirm Assignment & Forward'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
