import * as net from "net";
import { spawn, ChildProcess } from "child_process";

// =====================================
// CONFIGURATION
// =====================================

const PORT: number = 5000;
const MAGIC_NUMBER: number = 0xFEED;
const VIDEO_DEVICE: string = "/dev/video20";

const FFMPEG_ARGS = [
    // --- INPUT ---
    '-f', 'image2pipe',      // Input format: stream of images via pipe
    '-vcodec', 'mjpeg',      // Input codec: Motion JPEG
    '-probesize', '32768',   // Limit probing to 32 KB for faster startup
    '-analyzeduration', '0', // Skip analysis, start immediately
    '-fflags', 'nobuffer',   // Discard buffered frames to reduce latency
    '-flags', 'low_delay',   // Optimize for low-latency streaming
    '-i', '-',               // Read input from stdin

    // --- OUTPUT ---
    '-map', '0',             // Map all streams from input
    '-vcodec', 'rawvideo',   // Output codec: uncompressed raw video
    '-pix_fmt', 'yuv420p',   // Pixel format
    '-threads', '0',         // Auto-detect optimal thread count
    '-f', 'v4l2',            // Output format: Video4Linux2
    VIDEO_DEVICE             // Target virtual camera: /dev/video20
];

/** Currently active FFmpeg process (null if no stream active) */
let activeFFmpeg: ChildProcess | null = null;

// =====================================
// FFMPEG MANAGEMENT
// =====================================

/**
 * Terminate an FFmpeg process
 * @param instance - The FFmpeg child process to kill
 * @returns Promise that resolves when the process has fully exited
 */
function killFFmpeg(instance: ChildProcess | null): Promise<void>{
    return new Promise((resolve) => {
        if (!instance || instance.killed){
            resolve();
            return;
        }

        console.log("[-] Killing FFmpeg...");

        // Close stdin pipe to signal end-of-stream
        if (instance.stdin && !instance.stdin.destroyed){
            instance.stdin.destroy();
        }

        // Wait for clean process termination
        const onExit = () => {
            instance.removeAllListeners('close');
            console.log("[-] FFmpeg is dead");
            resolve()
        }

        instance.once('close', onExit);

        // Force kill
        instance.kill('SIGKILL')
    })

    
}

// =====================================
// TCP SERVER
// =====================================
const server = net.createServer(async (socket: net.Socket) => {
    console.log(`[+] Phone connected: ${socket.remoteAddress}`);
    socket.setNoDelay(true); // Disable Nagle's algorithm for real-time streaming

    // --- CLEANUP PREVIOUS SESSION ---
    if (activeFFmpeg){
        console.log("[*] Cleaning previous session...");
        await killFFmpeg(activeFFmpeg);
        activeFFmpeg = null;

        // Pause to let the Kernel release /dev/video20
        await new Promise(r => setTimeout(r, 200));
    }

    // --- SPAWN FFMPEG PROCESS ---
    const ffmpeg = spawn("ffmpeg", FFMPEG_ARGS);
    activeFFmpeg = ffmpeg;

    // Handle FFmpeg crashes
    ffmpeg.on('close', (code) => {
        // (0 = clean, 255 = SIGKILL, null = ongoing)
        if (code !== 0 && code !== 255 && code !== null) {
            console.log(`[!] FFmpeg exited with code ${code}`);
        }
    })

    // Prevent crash if pipe breaks
    ffmpeg.stdin?.on("error", () => {})

    // Only log errors if they aren't standard startup info
    ffmpeg.stderr.on('data', (data) => {
        const msg = data.toString();
        if (msg.includes('Error') || msg.includes('Invalid')) {
             console.error(`[FFMPEG Error] ${msg}`);
        }
    });

    /** 
     * Accumulates incoming TCP data chunks
     * Protocol: [2 bytes magic][4 bytes length][N bytes JPEG data]
     */
    let buffer = Buffer.alloc(0);

    // --- INCOMING DATA HANDLER ---
    socket.on('data', (chunk: Buffer) => {
        if (ffmpeg.killed || ffmpeg.exitCode !== null) return;

        buffer = Buffer.concat([buffer, chunk]);

        while (true) {
            // If there's no [MAGIC_NUMBER (2 bytes)] + [LENGTH (4 bytes)]
            if (buffer.length < 6) break;
            
            // Check magic number
            const magic = buffer.readUInt16BE(0);
            if (magic !== MAGIC_NUMBER){
                console.error(`[!] Protocol mismatch`);
                socket.destroy();
                return;
            }
            
            // Check image size
            const length = buffer.readUInt32BE(2);
            if (buffer.length < 6 + length) break;

            // Extract jpeg
            const jpegData = buffer.subarray(6, 6 + length)

            // Send to FFmpeg
            if (ffmpeg.stdin && !ffmpeg.stdin.destroyed) {
                ffmpeg.stdin.write(jpegData);
            }

            // Remove frame from buffer
            buffer = buffer.subarray(6 + length);
        }
    })

    // --- CONNECTION CLOSED ---
    socket.on('end', async () => {
        console.log(`[-] Phone disconnected`);
        await killFFmpeg(ffmpeg)

        if (activeFFmpeg === ffmpeg){
            activeFFmpeg = null
        }
    })

    // --- CONNECTION ERROR ---
    socket.on('error', async (err: Error) => {
        console.error(`[-] Socket error: ${err.message}`);
        await killFFmpeg(ffmpeg);

        if (activeFFmpeg === ffmpeg){
            activeFFmpeg = null
        }
    });
});

// =====================================
// SERVER STARTUP
// =====================================
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[OK] BrokieCam Server running on port ${PORT}`);
    console.log(`[OK] Target: ${VIDEO_DEVICE}`);
})