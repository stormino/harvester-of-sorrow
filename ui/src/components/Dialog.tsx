import { useEffect, useId, useRef, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { Button } from './Button';
import { CloseIcon } from './Icons';
import s from './Dialog.module.css';

interface DialogProps {
  open: boolean;
  title: string;
  onClose: () => void;
  width?: number;
  footer?: ReactNode;
  children: ReactNode;
  testId?: string;
}

/** Modal dialog: Escape and backdrop click close it; focus moves into it and back. */
export function Dialog({ open, title, onClose, width = 520, footer, children, testId }: DialogProps) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    if (!open) return;
    const previous = document.activeElement as HTMLElement | null;
    const first = panel.current?.querySelector<HTMLElement>(
      'input, button:not([data-dialog-close]), [tabindex]:not([tabindex="-1"])');
    (first ?? panel.current)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCloseRef.current();
    };
    document.addEventListener('keydown', onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKey);
      document.body.style.overflow = overflow;
      previous?.focus?.();
    };
  }, [open]);

  if (!open) return null;
  return createPortal(
    <div className={s.overlay} onMouseDown={(e) => { if (e.target === e.currentTarget) onClose(); }}>
      <div ref={panel} className={s.panel} style={{ ['--w' as string]: `${width}px` }} role="dialog" aria-modal="true"
        aria-labelledby={titleId} tabIndex={-1} data-testid={testId}>
        <div className={s.header}>
          <h3 className={s.title} id={titleId}>{title}</h3>
          <Button variant="ghost" size="md" iconOnly icon={<CloseIcon size={16} />} onClick={onClose}
            aria-label="Close" data-dialog-close />
        </div>
        <div className={s.body}>{children}</div>
        {footer && <div className={s.footer}>{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

interface ConfirmProps {
  open: boolean;
  title: string;
  message: ReactNode;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

export function ConfirmDialog({ open, title, message, confirmLabel, danger, busy, onConfirm, onClose }: ConfirmProps) {
  return (
    <Dialog open={open} title={title} onClose={onClose} width={440} testId="confirm-dialog"
      footer={
        <>
          <Button variant="outline" size="lg" onClick={onClose}>Cancel</Button>
          <Button variant={danger ? 'dangerSolid' : 'primary'} size="lg" loading={busy} onClick={onConfirm}
            data-testid="confirm-dialog-confirm">
            {confirmLabel}
          </Button>
        </>
      }>
      <p className={s.message}>{message}</p>
    </Dialog>
  );
}
