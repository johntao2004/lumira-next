import type { ReactNode } from "react";

type EditorSurfaceProps = {
  children: ReactNode;
  toolbar?: ReactNode;
  footer?: ReactNode;
  className?: string;
  contentClassName?: string;
};

/** Shared frame for platform editors, with optional toolbar and footer regions. */
export function EditorSurface({
  children,
  toolbar,
  footer,
  className,
  contentClassName,
}: EditorSurfaceProps) {
  const rootClassName = ["editor-surface", className].filter(Boolean).join(" ");
  const bodyClassName = ["editor-surface-content", contentClassName].filter(Boolean).join(" ");

  return (
    <div className={rootClassName}>
      {toolbar != null && <div className="editor-surface-toolbar">{toolbar}</div>}
      <div className={bodyClassName}>{children}</div>
      {footer != null && <div className="editor-surface-footer">{footer}</div>}
    </div>
  );
}
