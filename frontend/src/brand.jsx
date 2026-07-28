// brand.jsx — the ShiftSmith wordmark: the logo mark plus the name.
//
// The mark is the single logo asset (`public/logo.svg`), referenced by URL rather
// than inlined as JSX so there is exactly one drawing of it: the same file is the
// favicon (see `index.html`) and — copied to `backend/.../typst/logo.svg` — the mark
// printed in the PDF export's footer.
//
// The image is decorative: the name sits right beside it as text, so an empty `alt`
// keeps screen readers from reading the brand twice.
export function Brand({ className = 'brand' }) {
  return (
    <div className={className}>
      <img className="logo" src="/logo.svg" alt="" />
      <span className="brand-name"><b>Shift</b>Smith</span>
    </div>
  );
}
