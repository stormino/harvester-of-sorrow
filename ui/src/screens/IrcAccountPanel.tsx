import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useState } from 'react';
import { api } from '../api/client';
import { keys, useIrcIdentity, useIrcNetworks } from '../api/queries';
import type { IrcNetwork } from '../api/types';
import { Button } from '../components/Button';
import { TextField } from '../components/Input';
import { Switch } from '../components/Switch';
import { useToast } from '../components/Toast';
import inputStyles from '../components/Input.module.css';
import s from './SettingsScreen.module.css';

/** Settings › IRC account: the registered nick for XDCC channels that need one, and each network's state. */
export function IrcAccountPanel() {
  const identity = useIrcIdentity();
  const networks = useIrcNetworks();
  const qc = useQueryClient();
  const { toast } = useToast();
  const ids = { nick: useId(), pw: useId(), mail: useId() };

  const [nick, setNick] = useState('');
  const [password, setPassword] = useState('');
  const [email, setEmail] = useState('');
  const [autoRegister, setAutoRegister] = useState(false);

  const id = identity.data;
  useEffect(() => {
    if (!id) return;
    setNick(id.nick);
    setEmail(id.email ?? '');
    setAutoRegister(id.autoRegister);
    setPassword('');
  }, [id]);

  const refresh = () => {
    qc.invalidateQueries({ queryKey: keys.ircIdentity });
    qc.invalidateQueries({ queryKey: keys.ircNetworks });
  };

  const save = useMutation({
    mutationFn: () => api.saveIrcIdentity({ nick: nick.trim(), password, email: email.trim(), autoRegister }),
    onSuccess: () => { toast('IRC account saved', 'success'); refresh(); },
    onError: (e) => toast((e as Error).message, 'error'),
  });
  const clear = useMutation({
    mutationFn: api.clearIrcIdentity,
    onSuccess: () => { toast('IRC account removed', 'success'); refresh(); },
    onError: (e) => toast((e as Error).message, 'error'),
  });

  const dirty = !!id && (nick.trim() !== id.nick || password !== '' || email.trim() !== (id.email ?? '')
    || autoRegister !== id.autoRegister);
  const canSave = nick.trim() !== '' && (password !== '' || !!id?.passwordSet) && (!autoRegister || email.trim() !== '');

  return (
    <section className={s.panel} aria-labelledby="irc-h" data-testid="irc-account">
      <h3 id="irc-h" className={`${s.h3} ${s.h3tight}`}>IRC Account</h3>
      <p className={s.desc}>
        Some XDCC channels only let registered nicks in. HoS uses this nick only on those networks;
        everywhere else it stays anonymous. With &ldquo;Register the nick where needed&rdquo; on, HoS registers it
        with the e-mail below on networks where it is not registered yet.
      </p>

      {id?.origin === 'ENVIRONMENT' && (
        <p className={s.desc}>Currently from the <code>XDCC_IRC_*</code> variables. Saving here takes over.</p>
      )}

      <form className={s.grid} onSubmit={(e) => { e.preventDefault(); if (canSave) save.mutate(); }}>
        <div>
          <label className={inputStyles.label} htmlFor={ids.nick}>Nick</label>
          <TextField id={ids.nick} value={nick} onChange={(e) => setNick(e.target.value)} autoComplete="username"
            placeholder="MyNick" spellCheck={false} />
        </div>
        <div>
          <label className={inputStyles.label} htmlFor={ids.pw}>Password</label>
          <TextField id={ids.pw} type="password" value={password} onChange={(e) => setPassword(e.target.value)}
            autoComplete="new-password" placeholder={id?.passwordSet ? '•••••••• (unchanged)' : 'at least 5 characters'} />
        </div>
        <div className={s.span2}>
          <label className={inputStyles.label} htmlFor={ids.mail}>E-mail</label>
          <TextField id={ids.mail} type="email" value={email} onChange={(e) => setEmail(e.target.value)}
            autoComplete="email" placeholder="only used to register the nick" />
        </div>
        <div className={s.span2}>
          <Switch checked={autoRegister} onChange={setAutoRegister}>
            Register the nick where needed
          </Switch>
        </div>
        <div className={`${s.span2} ${s.actions}`}>
          <Button type="submit" disabled={!canSave || !dirty} loading={save.isPending}>Save</Button>
          {id?.origin === 'SETTINGS' && (
            <Button variant="ghost" loading={clear.isPending} onClick={() => clear.mutate()}>Remove</Button>
          )}
        </div>
      </form>

      {networks.data && networks.data.length > 0 && (
        <>
          <h4 className={s.h4}>Networks</h4>
          <div className={s.networks}>
            {networks.data.map((n) => <NetworkRow key={n.host} network={n} onChange={refresh} />)}
          </div>
        </>
      )}
    </section>
  );
}

const STATE: Record<string, { text: string; tone: 'ok' | 'warn' | 'bad' | 'muted' }> = {
  ANONYMOUS: { text: 'not needed', tone: 'muted' },
  LOGGING_IN: { text: 'logging in…', tone: 'muted' },
  LOGGED_IN: { text: 'logged in', tone: 'ok' },
  NEEDS_CONFIRM: { text: 'waiting for the e-mail code', tone: 'warn' },
  FAILED: { text: 'login failed', tone: 'bad' },
  NO_ACCOUNT: { text: 'needs a nick: set one above', tone: 'warn' },
};

function NetworkRow({ network: n, onChange }: { network: IrcNetwork; onChange: () => void }) {
  const { toast } = useToast();
  const [code, setCode] = useState('');
  const codeId = useId();
  const st = !n.connected && n.state === 'LOGGED_IN'
    ? { text: 'ready, logs in when needed', tone: 'ok' as const }
    : STATE[n.state] ?? { text: n.state, tone: 'muted' as const };

  const confirm = useMutation({
    mutationFn: () => api.confirmIrcNetwork(n.host, code.trim()),
    onSuccess: (r) => {
      setCode('');
      toast(r.state === 'LOGGED_IN' ? `Nick confirmed on ${n.host}. Retry the download.` : `Sent to ${n.host}`, 'success');
      onChange();
    },
    onError: (e) => toast((e as Error).message, 'error'),
  });
  const forget = useMutation({
    mutationFn: () => api.forgetIrcNetwork(n.host),
    onSuccess: onChange,
    onError: (e) => toast((e as Error).message, 'error'),
  });

  return (
    <div className={s.network} data-testid="irc-network" data-host={n.host} data-state={n.state}>
      <div className={s.netHead}>
        <span className={s.netHost}>{n.host}</span>
        <span className={s.netNick}>{n.nick ?? ''}</span>
        <span className={`${s.netState} ${s[st.tone]}`}>{st.text}</span>
        {n.needsAccount && (
          <Button variant="ghost" size="sm" loading={forget.isPending} onClick={() => forget.mutate()}
            title="Stop logging in here until a channel asks again">Forget</Button>
        )}
      </div>
      {n.message && n.state !== 'LOGGED_IN' && <p className={s.netMsg}>{n.message}</p>}
      {n.state === 'NEEDS_CONFIRM' && (
        <form className={s.confirm} onSubmit={(e) => { e.preventDefault(); if (code.trim()) confirm.mutate(); }}>
          <label className={inputStyles.label} htmlFor={codeId}>Code from the e-mail</label>
          <div className={s.confirmRow}>
            <TextField id={codeId} value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code"
              spellCheck={false} />
            <Button type="submit" disabled={!code.trim()} loading={confirm.isPending}>Confirm</Button>
          </div>
        </form>
      )}
    </div>
  );
}
