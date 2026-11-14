import { createContext, useContext, useEffect, useMemo, useReducer, useState, type ReactNode } from 'react';
import { EVENTS_URL } from '../api/client';
import type { Task } from '../api/types';
import { initialTaskState, taskReducer, type TaskEvent } from './taskStore';

export type ConnectionState = 'connecting' | 'connected' | 'disconnected';

interface TaskStreamValue {
  tasks: Task[];
  ready: boolean;
  connection: ConnectionState;
  /** Merge tasks returned by REST calls (queue/cancel/retry) before the SSE echo arrives. */
  upsert: (tasks: Task[]) => void;
}

const Ctx = createContext<TaskStreamValue | null>(null);

const EVENT_NAMES = ['task.created', 'task.updated', 'task.removed'] as const;

/**
 * Owns the single EventSource for the app. EventSource reconnects by itself;
 * every (re)connect starts with a `snapshot`, so state is always consistent.
 */
export function TaskStreamProvider({ children }: { children: ReactNode }) {
  const [state, dispatch] = useReducer(taskReducer, initialTaskState);
  const [connection, setConnection] = useState<ConnectionState>('connecting');

  useEffect(() => {
    let es: EventSource | null = null;
    let retryTimer: ReturnType<typeof setTimeout> | undefined;
    let closed = false;

    const open = () => {
      es = new EventSource(EVENTS_URL);
      es.addEventListener('open', () => setConnection('connected'));
      es.addEventListener('error', () => {
        setConnection('disconnected');
        // The browser retries on its own unless the server answered with an error status.
        if (es && es.readyState === EventSource.CLOSED && !closed) {
          retryTimer = setTimeout(open, 3000);
        }
      });
      es.addEventListener('snapshot', (e) => {
        setConnection('connected');
        dispatch({ type: 'snapshot', tasks: JSON.parse((e as MessageEvent).data) });
      });
      for (const name of EVENT_NAMES) {
        es.addEventListener(name, (e) => {
          dispatch({ type: name, task: JSON.parse((e as MessageEvent).data) } as TaskEvent);
        });
      }
    };
    open();
    return () => {
      closed = true;
      clearTimeout(retryTimer);
      es?.close();
    };
  }, []);

  const value = useMemo<TaskStreamValue>(() => ({
    tasks: Object.values(state.tasks),
    ready: state.ready,
    connection,
    upsert: (tasks) => dispatch({ type: 'upsertMany', tasks }),
  }), [state, connection]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useTaskStream(): TaskStreamValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useTaskStream outside TaskStreamProvider');
  return v;
}
