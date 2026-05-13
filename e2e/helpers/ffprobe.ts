import { execFile } from 'node:child_process';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

export interface StreamInfo {
  codec_type: 'video' | 'audio' | 'subtitle' | string;
  codec_name: string;
  width?: number;
  height?: number;
  channels?: number;
  duration?: string;
  tags?: { language?: string; title?: string; [key: string]: string | undefined };
}

export interface ProbeResult {
  format: {
    duration: string;
    size: string;
    bit_rate: string;
    filename: string;
  };
  streams: StreamInfo[];
}

/** Run ffprobe on a file and return parsed JSON. Throws on non-zero exit. */
export async function probe(filePath: string): Promise<ProbeResult> {
  const { stdout } = await execFileAsync('ffprobe', [
    '-v', 'error',
    '-print_format', 'json',
    '-show_streams',
    '-show_format',
    filePath,
  ]);
  return JSON.parse(stdout) as ProbeResult;
}
