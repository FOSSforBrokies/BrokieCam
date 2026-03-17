import * as net from "net";
import { spawn, ChildProcess, exec } from "child_process";
import { promisify } from "util";
import { log } from "./logger.js";

const execAsync = promisify(exec)

// =====================================
// CONFIGURATION
// =====================================

const PORT: number = 5000;
const VIDEO_DEVICE: string = "/dev/video20";

const FFMPEG_ARGS = [
    // --- INPUT ---
    '-f', 'h264',            // Input format: Raw H.264 video stream
    '-probesize', '32768',   // Limit probing to 32 KB for faster startup
    '-analyzeduration', '0', // Skip analysis, start immediately
    '-fflags', 'nobuffer',   // Discard buffered frames to reduce latency
    '-flags', 'low_delay',   // Optimize for low-latency streaming
    '-use_wallclock_as_timestamps', '1', // Use the server's clock for timestamps (raw H.264 lacks PTS/DTS)
    '-i', '-',               // Read input from stdin

    // --- OUTPUT ---
    '-vcodec', 'rawvideo',   // Output codec: uncompressed raw video for v4l2
    '-pix_fmt', 'yuv420p',   // Standard pixel format for virtual cameras
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

        log("INFO", "FFMPEG", "Killing FFmpeg...");

        // Close stdin pipe to signal end-of-stream
        if (instance.stdin && !instance.stdin.destroyed){
            instance.stdin.destroy();
        }

        // Wait for clean process termination
        const onExit = () => {
            instance.removeAllListeners('close');
            log("INFO", "FFMPEG", "FFmpeg is dead")
            resolve()
        }

        instance.once('close', onExit);

        // Force kill
        instance.kill('SIGKILL')
    })

    
}

// =====================================
// ADB TUNNEL RECOVERY
// =====================================
let isRecoveringAdb = false;

/**
 * Polls ADB until the device returns, then rebuilds the reverse tunnel
 */
async function restoreAdbTunnel(){
    if (isRecoveringAdb) return;

    isRecoveringAdb = true

    log("WARN", "ADB", "USB connection lost. Waiting for device to return...");

    while (true){
        try{
            const { stdout } = await execAsync("adb devices");

            if (stdout.includes("\tdevice")){
                log("INFO", "ADB", "Device detected! Rebuilding ADB tunneling...");

                await execAsync(`adb reverse tcp:${PORT} tcp:${PORT}`);
                log("INFO", "ADB", `ADB reverse tunnel restored on port ${PORT}`);

                break
            }
        } catch(err){
            // TODO
        }

        await new Promise(r => setTimeout(r, 500));
    }

    isRecoveringAdb = false
}

// =====================================
// TCP SERVER
// =====================================
const server = net.createServer(async (socket: net.Socket) => {
    log("INFO", "TCP", `Phone connected: ${socket.remoteAddress}`);
    socket.setNoDelay(true); // Disable Nagle's algorithm for real-time streaming

    // --- CLEANUP PREVIOUS SESSION ---
    if (activeFFmpeg){
        log("INFO", "SERVER", "Cleaning previous session...");
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
            log("WARN", "FFMPEG", `FFmpeg exited with code ${code}`);
        }
    })

    // Prevent crash if pipe breaks
    ffmpeg.stdin?.on("error", () => {})

    // Only log errors if they aren't standard startup info
    ffmpeg.stderr.on('data', (data) => {
        const msg = data.toString();
        if (msg.includes('Error') || msg.includes('Invalid')) {
            log("ERROR", "FFMPEG", msg);
        }
    });

    // --- UNMUTED FFMPEG LOGS (USE FOR DEBUGGING) ---
    // ffmpeg.stderr.on('data', (data) => {
    //     const msg = data.toString().trim();
    //     if (msg) {
    //         console.log(`[FFMPEG DEBUG] ${msg}`);
    //     }
    // });

    // --- INCOMING DATA HANDLER ---
    socket.on('data', (chunk: Buffer) => {
        if (ffmpeg.killed || ffmpeg.exitCode !== null) return;

        if (ffmpeg.stdin && !ffmpeg.stdin.destroyed) {
            ffmpeg.stdin.write(chunk);
        }   
    })

    // --- CONNECTION CLOSED ---
    socket.on('end', async () => {
        log("INFO", "TCP", "Phone disconnected");
        await killFFmpeg(ffmpeg)

        if (activeFFmpeg === ffmpeg){
            activeFFmpeg = null
        }

        restoreAdbTunnel();
    })

    // --- CONNECTION ERROR ---
    socket.on('error', async (err: Error) => {
        log("ERROR", "TCP", `Socket error: ${err.message}`);
        await killFFmpeg(ffmpeg);

        if (activeFFmpeg === ffmpeg){
            activeFFmpeg = null
        }

        restoreAdbTunnel();
    });
});

// =====================================
// SERVER STARTUP
// =====================================
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[OK] BrokieCam Server running on port ${PORT}`);
    console.log(`[OK] Target: ${VIDEO_DEVICE}`);
})