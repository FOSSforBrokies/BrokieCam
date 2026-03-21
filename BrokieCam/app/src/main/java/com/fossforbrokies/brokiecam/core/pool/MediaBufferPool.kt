package com.fossforbrokies.brokiecam.core.pool

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe object pool for managing reusable media buffers.
 *
 * Pre-allocates a fixed number of byte arrays.
 *
 * @property poolSize Total number of buffers to pre-allocate in the pool.
 * @property bufferSize Fixed size (in bytes) of each individual buffer array.
 */
class MediaBufferPool(poolSize: Int, bufferSize: Int) {

    /**
     * Reusable wrapper around [ByteArray] that also holds media-specific metadata.
     *
     * @property data Underlying byte array used for storing the raw media payload.
     */
    inner class PooledBuffer(val data: ByteArray){
        /** Valid number of bytes currently stored in [data] */
        var length: Int = 0

        /** The presentation timestamp (PTS) of the media frame in milliseconds. */
        var presentationTimeMs: Long = 0

        /**
         * Clears the metadata to prevent cross-contamination for the next consumer
         */
        fun release(){
            // Clean up metadata for the next user
            length = 0
            presentationTimeMs = 0

            // Return to the lock-free queue
            freeQueue.offer(this)
        }
    }

    /** Lock-free queue used for high-performance, non-blocking acquisitions and releases across multiple threads */
    private val freeQueue = ConcurrentLinkedQueue<PooledBuffer>()

    init {
        // Pre-allocates the entire pool on startup.
        for (i in 0 until poolSize){
            freeQueue.offer(PooledBuffer(ByteArray(bufferSize)))
        }
    }

    /**
     * Grabs an available buffer.
     *
     * @return [PooledBuffer] if the pool has space, or
     * * "null" if no buffer is currently available
     */
    fun acquire(): PooledBuffer? {
        return freeQueue.poll()
    }

}