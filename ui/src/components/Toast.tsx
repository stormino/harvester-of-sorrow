import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { AlertIcon, CloseIcon } from './Icons';
import s from './Toast.module.css';

export type ToastVariant = 'success' | 'error' | 'warning' | 'contrast';
export type ToastPlacement = 'end' | 'mid';

interface ToastItem { id: number; msg: string; variant: ToastVariant; placement: ToastPlacement }

interface ToastApi {
  /** Shows a toast; returns its id so it can be dismissed early. */
  toast: (msg: string, variant?: ToastVariant, opts?: { placement?: ToastPlacement; durationMs?: number }) => number;
  dismiss: (id: number) => void;
}

const Ctx = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([]);
  const nextId = useRef(0);

  const dismiss = useCallback((id: number) => setItems((xs) => xs.filter((x) => x.id !== id)), []);

  const toast = useCallback<ToastApi['toast']>((msg, variant = 'contrast', opts = {}) => {
    const id = ++nextId.current;
    const placement = opts.placement ?? (variant === 'error' ? 'mid' : 'end');
    setItems((xs) => [...xs, { id, msg, variant, placement }]);
    setTimeout(() => dismiss(id), opts.durationMs ?? (variant === 'error' ? 5000 : 3500));
    return id;
  }, [dismiss]);

  const api = useMemo(() => ({ toast, dismiss }), [toast, dismiss]);

  const render = (placement: ToastPlacement) => (
    <div className={s[placement]} aria-live={placement === 'mid' ? 'assertive' : 'polite'}>
      {items.filter((t) => t.placement === placement).map((t) => (
        <div key={t.id} className={`${s.toast} ${s[t.variant]}`} role={t.variant === 'error' ? 'alert' : 'status'}
          data-testid="toast" data-variant={t.variant}>
          {placement === 'mid' ? <span className={s.icon}><AlertIcon size={17} /></span> : <span className={s.dot} />}
          <span className={s.msg}>{t.msg}</span>
          <button type="button" className={s.close} onClick={() => dismiss(t.id)} aria-label="Dismiss">
            <CloseIcon size={13} />
          </button>
        </div>
      ))}
    </div>
  );

  return (
    <Ctx.Provider value={api}>
      {children}
      {render('end')}
      {render('mid')}
    </Ctx.Provider>
  );
}

export function useToast(): ToastApi {
  const v = useContext(Ctx);
  if (!v) throw new Error('useToast outside ToastProvider');
  return v;
}
