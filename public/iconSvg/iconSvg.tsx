export const ActivityIcon = ({ className = "w-5 h-5" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M22 12h-4l-3 9L9 3l-3 9H2" /></svg>
);
export const UserIcon = ({ className = "w-5 h-5" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2" /><circle cx="12" cy="7" r="4" /></svg>
);
export const ClockIcon = ({ className = "w-5 h-5" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10" /><polyline points="12 6 12 12 16 14" /></svg>
);
export const RefreshIcon = ({ className = "w-5 h-5" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8" /><path d="M21 3v5h-5" /><path d="M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16" /><path d="M3 21v-5h5" /></svg>
);
export const EditIcon = ({ className = "w-3 h-3" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M17 3a2.828 2.828 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5L17 3z" /></svg>
);
export const SuccessIcon = ({ className = "w-12 h-12" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="#10b981" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><polyline points="20 6 9 17 4 12" /></svg>
);
export const ErrorIcon = ({ className = "w-12 h-12" }) => (
  <svg xmlns="http://www.w3.org/2000/svg" className={className} viewBox="0 0 24 24" fill="none" stroke="#ef4444" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><circle cx="12" cy="12" r="10" /><line x1="15" y1="9" x2="9" y2="15" /><line x1="9" y1="9" x2="15" y2="15" /></svg>
);
export const RespiratoryRateIcon = ({ className = "w-8 h-8" }) => (
  <svg
    className={className}
    viewBox="0 0 24 24"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
  >
    {/* Trachea (The windpipe) */}
    <path
      d="M10.5 3V7M13.5 3V7"
      stroke="#0088d6"
      strokeWidth="1.5"
      strokeLinecap="round"
    />

    {/* Left Lung - Defined by an organic, asymmetrical curve */}
    <path
      d="M10.5 7.5C8 7.5 4.5 8.5 4.5 14C4.5 19 7.5 21 10 21C11.5 21 12 19.5 12 18V10"
      stroke="#0088d6"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />

    {/* Right Lung - Slightly wider at the base, like a real lung */}
    <path
      d="M13.5 7.5C16 7.5 19.5 8.5 19.5 14C19.5 19 16.5 21 14 21C12.5 21 12 19.5 12 18V10"
      stroke="#0088d6"
      strokeWidth="1.5"
      strokeLinecap="round"
      strokeLinejoin="round"
    />

    {/* The Internal "Y" Bronchi - Following the reference sketch */}
    <path
      d="M10.5 7L8 10C7 11.5 7 14.5 8.5 16.5M13.5 7L16 10C17 11.5 17 14.5 15.5 16.5"
      stroke="#0088d6"
      strokeWidth="1.2"
      strokeLinecap="round"
      strokeOpacity="0.7"
    />
  </svg>
);
export const PerfusionIndexIcon = ({ className = "w-6.5 h-6.5" }) => (
  <svg className={className} viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
    <path d="M2 17C4 17 5 10 8 10C11 10 12 17 15 17C18 17 19 12 22 12" stroke="#0088d6" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
    <path d="M2 12C4 12 5 7 8 7C11 7 12 12 15 12C18 12 19 9 22 9" stroke="#0088d6" strokeWidth="1" strokeOpacity="0.4" strokeLinecap="round" strokeLinejoin="round" />
    {/* <circle cx="21" cy="6" r="1.5" fill="#0088d6"/> */}
  </svg>
);