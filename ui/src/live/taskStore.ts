import type { Task } from '../api/types';

/** Live download state, keyed by task id. */
export interface TaskState {
  /** True once the first snapshot has arrived. */
  ready: boolean;
  tasks: Record<string, Task>;
}

export type TaskEvent =
  | { type: 'snapshot'; tasks: Task[] }
  | { type: 'task.created' | 'task.updated'; task: Task }
  | { type: 'task.removed'; task: Pick<Task, 'id'> }
  | { type: 'upsertMany'; tasks: Task[] };

export const initialTaskState: TaskState = { ready: false, tasks: {} };

/**
 * Pure reducer for the SSE stream. A snapshot replaces everything; created and
 * updated events upsert by id (each carries the full task, sub-tasks included);
 * removed deletes. `upsertMany` merges REST responses (e.g. after queueing).
 */
export function taskReducer(state: TaskState, event: TaskEvent): TaskState {
  switch (event.type) {
    case 'snapshot': {
      const tasks: Record<string, Task> = {};
      for (const t of event.tasks) tasks[t.id] = t;
      return { ready: true, tasks };
    }
    case 'task.created':
    case 'task.updated':
      return { ...state, tasks: { ...state.tasks, [event.task.id]: event.task } };
    case 'upsertMany': {
      if (event.tasks.length === 0) return state;
      const tasks = { ...state.tasks };
      for (const t of event.tasks) tasks[t.id] = t;
      return { ...state, tasks };
    }
    case 'task.removed': {
      if (!(event.task.id in state.tasks)) return state;
      const tasks = { ...state.tasks };
      delete tasks[event.task.id];
      return { ...state, tasks };
    }
    default:
      return state;
  }
}
