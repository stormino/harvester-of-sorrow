import { describe, expect, it } from 'vitest';
import type { Task } from '../api/types';
import { initialTaskState, taskReducer } from './taskStore';

const task = (id: string, over: Partial<Task> = {}): Task => ({
  id, source: 'VIXSRC', contentType: 'MOVIE', displayName: id, languages: ['en'],
  status: 'QUEUED', progress: 0, createdAt: '2026-01-01T00:00:00Z',
  canCancel: true, canRetry: false, subTasks: [], ...over,
});

describe('taskReducer', () => {
  it('snapshot replaces all tasks and marks ready', () => {
    const s1 = taskReducer(initialTaskState, { type: 'snapshot', tasks: [task('a'), task('b')] });
    expect(s1.ready).toBe(true);
    expect(Object.keys(s1.tasks)).toEqual(['a', 'b']);
    const s2 = taskReducer(s1, { type: 'snapshot', tasks: [task('c')] });
    expect(Object.keys(s2.tasks)).toEqual(['c']);
  });

  it('task.created adds an unknown task', () => {
    const s = taskReducer(taskReducer(initialTaskState, { type: 'snapshot', tasks: [] }),
      { type: 'task.created', task: task('new') });
    expect(s.tasks.new.status).toBe('QUEUED');
  });

  it('task.updated upserts, including tasks never seen before', () => {
    const s = taskReducer(initialTaskState, { type: 'task.updated', task: task('x', { status: 'DOWNLOADING' }) });
    expect(s.tasks.x.status).toBe('DOWNLOADING');
  });

  it('task.updated replaces the whole task, so sub-task changes never clobber the parent', () => {
    const parent = task('p', {
      status: 'DOWNLOADING', progress: 40,
      subTasks: [{ id: 's1', type: 'AUDIO', displayName: 'Audio', status: 'QUEUED', progress: 0 }],
    });
    let s = taskReducer(initialTaskState, { type: 'snapshot', tasks: [parent] });
    s = taskReducer(s, {
      type: 'task.updated',
      task: { ...parent, progress: 45, subTasks: [{ ...parent.subTasks[0], status: 'DOWNLOADING', progress: 10 }] },
    });
    expect(s.tasks.p.status).toBe('DOWNLOADING');
    expect(s.tasks.p.progress).toBe(45);
    expect(s.tasks.p.subTasks[0].status).toBe('DOWNLOADING');
  });

  it('task.removed deletes; unknown ids are a no-op', () => {
    const s1 = taskReducer(initialTaskState, { type: 'snapshot', tasks: [task('a'), task('b')] });
    const s2 = taskReducer(s1, { type: 'task.removed', task: { id: 'a' } });
    expect(Object.keys(s2.tasks)).toEqual(['b']);
    expect(taskReducer(s2, { type: 'task.removed', task: { id: 'zzz' } })).toBe(s2);
  });

  it('upsertMany merges REST results', () => {
    const s1 = taskReducer(initialTaskState, { type: 'snapshot', tasks: [task('a')] });
    const s2 = taskReducer(s1, { type: 'upsertMany', tasks: [task('a', { status: 'CANCELLED' }), task('b')] });
    expect(s2.tasks.a.status).toBe('CANCELLED');
    expect(s2.tasks.b).toBeDefined();
    expect(taskReducer(s2, { type: 'upsertMany', tasks: [] })).toBe(s2);
  });
});
