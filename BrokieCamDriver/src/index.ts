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
const AUDIO_DEVICE: string = "BrokieCam_Sink"; // virtual audio sink

// --- PROTOCOL CONSTANTS ---
// Custom Protocol:
// - [2 Bytes] Magic Identifier (`0x42`, `0x43`)
// - [1 Byte]  Payload Type (`0x01` = Video, `0x02` = Audio)
// - [4 Bytes] Payload Length (32-bit Integer)
// - [N Bytes] The raw payload data (H.264 NAL unit or PCM Audio chunk)

const MAGIC_BYTE_1: number = 0x42; // 'B'
const MAGIC_BYTE_2: number = 0x43; // 'C'
const TYPE_VIDEO: number = 0x01;
const TYPE_AUDIO: number = 0x02;
const HEADER_SIZE: number = 7; // 2 Magic + 1 Type + 4 Length

const FFMPEG_VIDEO_ARGS = [
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

const FFMPEG_AUDIO_ARGS = [
    // --- INPUT ---
    '-f', 's16le',           // Input format: 16-bit signed little-endian PCM
    '-ar', '48000',          // Sample rate: 48.0 kHz
    '-ac', '1',              // Channels: 1 (Mono)
    '-probesize', '32',      // Extremely small probe size for instant start
    '-fflags', 'nobuffer',   // Discard buffered audio to reduce latency
    '-flags', 'low_delay',   // Optimize for low-latency streaming
    '-i', '-',               // Read input from stdin

    // --- OUTPUT ---
    '-f', 'pulse',           // Output format: PulseAudio
    AUDIO_DEVICE             // Target our custom virtual sink (BrokieCam_Sink)
];
/** Currently active FFmpeg processes (null if no stream active) */
let activeVideoProcess: ChildProcess | null = null;
let activeAudioProcess: ChildProcess | null = null;

// =====================================
// FFMPEG MANAGEMENT
// =====================================

/**
 * Terminate a child spawned process (ffmpeg or ffplay)
 * @param instance - The child process to kill
 * @returns Promise that resolves when the process has fully exited
 */
function killProcess(instance: ChildProcess | null, name: string): Promise<void>{
    return new Promise((resolve) => {
        if (!instance || instance.killed){
            resolve();
            return;
        }

        log("INFO", name, `Killing ${name}...`);

        // Close stdin pipe to signal end-of-stream
        if (instance.stdin && !instance.stdin.destroyed){
            instance.stdin.destroy();
        }

        // Wait for clean process termination
        const onExit = () => {
            instance.removeAllListeners('close');
            log("INFO", name, `${name} is dead`)
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
        } catch(err){}

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
    if (activeAudioProcess || activeVideoProcess){
        log("INFO", "SERVER", "Cleaning previous session...");

        await Promise.all([
            killProcess(activeVideoProcess, "FFMPEG (Video)"),
            killProcess(activeAudioProcess, "FFMPEG (Audio)")
        ])
        
        activeAudioProcess = null;
        activeVideoProcess = null;

        // Pause to let the Kernel release /dev/video20
        await new Promise(r => setTimeout(r, 200));
    }

    // --- SPAWN PROCESSES ---
    activeVideoProcess = spawn("ffmpeg", FFMPEG_VIDEO_ARGS);
    activeAudioProcess = spawn("ffmpeg", FFMPEG_AUDIO_ARGS);

    // Prevent crash if pipe breaks
    activeAudioProcess.stdin?.on("error", () => {})
    activeVideoProcess.stdin?.on("error", () => {})
    
    // Log FFmpeg/FFplay errors if they aren't standard startup info
    activeVideoProcess.stderr?.on('data', (data) => {
        const msg = data.toString();
        if (msg.includes('Error') || msg.includes('Invalid')) log("ERROR", "FFMPEG", msg);
    });


    /** 
     * Accumulates incoming TCP data chunks
     * Protocol: [MAGIC_BYTES (2 bytes)] + [TYPE (1 byte)] + [LENGTH (4 bytes)] + [H.264/PCM DATA (N bytes)]
     */
    let streamBuffer = Buffer.alloc(0);

    // --- INCOMING DATA HANDLER ---
    socket.on('data', (chunk: Buffer) => {    
        // Collect new chunk
        streamBuffer = Buffer.concat([streamBuffer, chunk]);

        while (true){
            if (streamBuffer.length < HEADER_SIZE) break;

            // Check magic bytes
            if (streamBuffer[0] !== MAGIC_BYTE_1 || streamBuffer[1] !== MAGIC_BYTE_2){
                log("WARN", "PARSER", "Invalid signature! Dropping byte to hunt for signature...");
                streamBuffer = streamBuffer.subarray(1);
                continue;
            }
            
            // Read metadata
            const type = streamBuffer[2];
            const payloadLength = streamBuffer.readInt32BE(3); // Big-Endian matches Kotlin
            const totalFrameSize = HEADER_SIZE + payloadLength;
            
            if (streamBuffer.length < totalFrameSize){
                break;
            }

            // Extract payload
            const payload = streamBuffer.subarray(HEADER_SIZE, totalFrameSize);

            // Route payload
            if (type === TYPE_VIDEO){
                if (activeVideoProcess && !activeVideoProcess.killed && activeVideoProcess.stdin) {
                    activeVideoProcess.stdin.write(payload);
                } 

            } else if (type === TYPE_AUDIO) { 
                if (activeAudioProcess && !activeAudioProcess.killed && activeAudioProcess.stdin) {
                    activeAudioProcess.stdin.write(payload);
                }
            } else{
                log("WARN", "PARSER", `Unknown payload type: ${type}`);
            }
            
            // Remove processed frame
            streamBuffer = streamBuffer.subarray(totalFrameSize);
        }
    })

    // --- CONNECTION CLOSED ---
    socket.on('end', async () => {
        log("INFO", "TCP", "Phone disconnected");

        await Promise.all([
            killProcess(activeVideoProcess, "FFMPEG (Video)"),
            killProcess(activeAudioProcess, "FFMPEG (Audio)")
        ]);

        activeVideoProcess = null;
        activeAudioProcess = null;

        restoreAdbTunnel();
    })

    // --- CONNECTION ERROR ---
    socket.on('error', async (err: Error) => {
        log("ERROR", "TCP", `Socket error: ${err.message}`);

        await Promise.all([
            killProcess(activeVideoProcess, "FFMPEG (Video)"),
            killProcess(activeAudioProcess, "FFMPEG (Audio)")
        ]);
        
        activeVideoProcess = null;
        activeAudioProcess = null;

        restoreAdbTunnel();
    });
});

// =====================================
// SERVER STARTUP
// =====================================
server.listen(PORT, '0.0.0.0', () => {
    console.log(`[OK] BrokieCam Server running on port ${PORT}`);
    console.log(`[OK] Video Target: ${VIDEO_DEVICE}`);
    console.log(`[OK] Audio Target: Virtual Mic (${AUDIO_DEVICE})`);
})