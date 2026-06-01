// icons.jsx — minimal stroke icon set.
const Ic = {};
function mkIcon(name, body) {
  Ic[name] = function (props) {
    const { size = 18, fill = false, ...rest } = props || {};
    return (
      <svg viewBox="0 0 24 24" fill={fill ? 'currentColor' : 'none'}
        stroke={fill ? 'none' : 'currentColor'} strokeWidth="1.8"
        strokeLinecap="round" strokeLinejoin="round"
        width={size} height={size} {...rest}>{body}</svg>
    );
  };
}

mkIcon('grid', <><rect x="3" y="3" width="7" height="7" rx="1.5"/><rect x="14" y="3" width="7" height="7" rx="1.5"/><rect x="3" y="14" width="7" height="7" rx="1.5"/><rect x="14" y="14" width="7" height="7" rx="1.5"/></>);
mkIcon('users', <><circle cx="9" cy="8" r="3.2"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0"/><path d="M16 5.2a3.2 3.2 0 0 1 0 6"/><path d="M17.5 13.4A5.5 5.5 0 0 1 20.5 19"/></>);
mkIcon('briefcase', <><rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 7V5.5A1.5 1.5 0 0 1 9.5 4h5A1.5 1.5 0 0 1 16 5.5V7"/><path d="M3 12h18"/></>);
mkIcon('timeline', <><path d="M3 6h18"/><path d="M3 12h18"/><path d="M3 18h18"/><rect x="6" y="4.5" width="7" height="3" rx="1.5" fill="currentColor" stroke="none"/><rect x="11" y="10.5" width="8" height="3" rx="1.5" fill="currentColor" stroke="none"/><rect x="5" y="16.5" width="6" height="3" rx="1.5" fill="currentColor" stroke="none"/></>);
mkIcon('sun', <><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></>);
mkIcon('moon', <path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z"/>);
mkIcon('search', <><circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2"/></>);
mkIcon('plus', <><path d="M12 5v14M5 12h14"/></>);
mkIcon('x', <><path d="M6 6l12 12M18 6L6 18"/></>);
mkIcon('chevL', <path d="M15 6l-6 6 6 6"/>);
mkIcon('chevR', <path d="M9 6l6 6-6 6"/>);
mkIcon('chevD', <path d="M6 9l6 6 6-6"/>);
mkIcon('trash', <><path d="M4 7h16M9 7V5a1 1 0 0 1 1-1h4a1 1 0 0 1 1 1v2M6 7l1 13a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-13"/></>);
mkIcon('repeat', <><path d="M17 2l3 3-3 3"/><path d="M20 5H8a4 4 0 0 0-4 4v1"/><path d="M7 22l-3-3 3-3"/><path d="M4 19h12a4 4 0 0 0 4-4v-1"/></>);
mkIcon('clock', <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/></>);
mkIcon('calendar', <><rect x="3" y="4.5" width="18" height="16" rx="2.5"/><path d="M3 9h18M8 2.5v4M16 2.5v4"/></>);
mkIcon('check', <path d="M5 12.5l4.5 4.5L19 7"/>);
mkIcon('alert', <><path d="M12 3l9 16H3l9-16Z"/><path d="M12 10v4M12 17.5v.01"/></>);
mkIcon('user', <><circle cx="12" cy="8" r="4"/><path d="M4.5 20a7.5 7.5 0 0 1 15 0"/></>);
mkIcon('sliders', <><path d="M4 6h10M18 6h2M4 12h2M10 12h10M4 18h7M15 18h5"/><circle cx="16" cy="6" r="2"/><circle cx="8" cy="12" r="2"/><circle cx="13" cy="18" r="2"/></>);
mkIcon('star', <path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 17l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9Z"/>);
mkIcon('ban', <><circle cx="12" cy="12" r="9"/><path d="M5.6 5.6l12.8 12.8"/></>);
mkIcon('palm', <><path d="M12 22V11"/><path d="M12 11c0-3 2-5 5-5M12 11c0 3-2 5-5 5M12 11c0-3-2-5-5-5M12 11c1-2.5 3.5-4 6-3.5"/><circle cx="12" cy="9.5" r="1.4" fill="currentColor" stroke="none"/></>);
mkIcon('zoomIn', <><circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2M11 8v6M8 11h6"/></>);
mkIcon('zoomOut', <><circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2M8 11h6"/></>);
mkIcon('move', <><path d="M12 3v18M3 12h18"/><path d="M9 6l3-3 3 3M9 18l3 3 3-3M6 9l-3 3 3 3M18 9l3 3-3 3"/></>);
mkIcon('warning2', <><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16v.01"/></>);
mkIcon('sparkles', <><path d="M12 3l1.6 4.4L18 9l-4.4 1.6L12 15l-1.6-4.4L6 9l4.4-1.6Z"/><path d="M18 14l.8 2.2L21 17l-2.2.8L18 20l-.8-2.2L15 17l2.2-.8Z"/></>);
mkIcon('folderPlus', <><path d="M3 7a2 2 0 0 1 2-2h4l2 2.5h8a2 2 0 0 1 2 2V18a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/><path d="M12 11.5v5M9.5 14h5"/></>);

export { Ic };
