/*
 * Copyright 2015 Distributed Ledger Group LLC.  Distributed as Licensed Company IP to DLG Group Members
 * pursuant to the August 7, 2015 Advisory Services Agreement and subject to the Company IP License terms
 * set forth therein.
 *
 * All other rights reserved.
 */

package core.messaging

import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import core.DummyTimestampingAuthority
import core.ThreadBox
import core.crypto.sha256
import core.node.TimestamperNodeService
import core.utilities.loggerFor
import java.security.KeyPair
import java.time.Instant
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import javax.annotation.concurrent.ThreadSafe
import kotlin.concurrent.thread

/**
 * An in-memory network allows you to manufacture [InMemoryNode]s for a set of participants. Each
 * [InMemoryNode] maintains a queue of messages it has received, and a background thread that dispatches
 * messages one by one to registered handlers. Alternatively, a messaging system may be manually pumped, in which
 * case no thread is created and a caller is expected to force delivery one at a time (this is useful for unit
 * testing).
 */
@ThreadSafe
class InMemoryNetwork {
    private var counter = 0   // -1 means stopped.
    private val handleNodeMap = HashMap<Handle, InMemoryNode>()
    // All messages are kept here until the messages are pumped off the queue by a caller to the node class.
    // Queues are created on-demand when a message is sent to an address: the receiving node doesn't have to have
    // been created yet. If the node identified by the given handle has gone away/been shut down then messages
    // stack up here waiting for it to come back. The intent of this is to simulate a reliable messaging network.
    private val messageQueues = HashMap<Handle, LinkedBlockingQueue<Message>>()

    val nodes: List<InMemoryNode> @Synchronized get() = handleNodeMap.values.toList()

    /**
     * Creates a node and returns the new object that identifies its location on the network to senders, and the
     * [InMemoryNode] that the recipient/in-memory node uses to receive messages and send messages itself.
     *
     * If [manuallyPumped] is set to true, then you are expected to call the [InMemoryNode.pump] method on the [InMemoryNode]
     * in order to cause the delivery of a single message, which will occur on the thread of the caller. If set to false
     * then this class will set up a background thread to deliver messages asynchronously, if the handler specifies no
     * executor.
     */
    @Synchronized
    fun createNode(manuallyPumped: Boolean): Pair<Handle, MessagingServiceBuilder<InMemoryNode>> {
        check(counter >= 0) { "In memory network stopped: please recreate."}
        val builder = createNodeWithID(manuallyPumped, counter) as Builder
        counter++
        val id = builder.id
        return Pair(id, builder)
    }

    /** Creates a node at the given address: useful if you want to recreate a node to simulate a restart */
    fun createNodeWithID(manuallyPumped: Boolean, id: Int): MessagingServiceBuilder<InMemoryNode> {
        return Builder(manuallyPumped, Handle(id))
    }

    @Synchronized
    private fun netSend(message: Message, recipients: MessageRecipients) {
        when (recipients) {
            is Handle -> getQueueForHandle(recipients).add(message)

            is AllPossibleRecipients -> {
                // This means all possible recipients _that the network knows about at the time_, not literally everyone
                // who joins into the indefinite future.
                for (handle in handleNodeMap.keys)
                    getQueueForHandle(handle).add(message)
            }
            else -> throw IllegalArgumentException("Unknown type of recipient handle")
        }
    }

    @Synchronized
    private fun netNodeHasShutdown(handle: Handle) {
        handleNodeMap.remove(handle)
    }

    @Synchronized
    private fun getQueueForHandle(recipients: Handle) = messageQueues.getOrPut(recipients) { LinkedBlockingQueue() }

    val everyoneOnline: AllPossibleRecipients = object : AllPossibleRecipients {}

    fun stop() {
        val nodes = synchronized(this) {
            counter = -1
            handleNodeMap.values.toList()
        }

        for (node in nodes)
            node.stop()

        handleNodeMap.clear()
        messageQueues.clear()
    }

    inner class Builder(val manuallyPumped: Boolean, val id: Handle) : MessagingServiceBuilder<InMemoryNode> {
        override fun start(): ListenableFuture<InMemoryNode> {
            synchronized(this@InMemoryNetwork) {
                val node = InMemoryNode(manuallyPumped, id)
                handleNodeMap[id] = node
                return Futures.immediateFuture(node)
            }
        }
    }

    class Handle(val id: Int) : SingleMessageRecipient {
        override fun toString() = "In memory node $id"
        override fun equals(other: Any?) = other is Handle && other.id == id
        override fun hashCode() = id.hashCode()
    }

    private var timestampingAdvert: LegallyIdentifiableNode? = null

    @Synchronized
    fun setupTimestampingNode(manuallyPumped: Boolean): Pair<LegallyIdentifiableNode, InMemoryNode> {
        check(timestampingAdvert == null)
        val (handle, builder) = createNode(manuallyPumped)
        val node = builder.start().get()
        TimestamperNodeService(node, DummyTimestampingAuthority.identity, DummyTimestampingAuthority.key)
        timestampingAdvert = LegallyIdentifiableNode(handle, DummyTimestampingAuthority.identity)
        return Pair(timestampingAdvert!!, node)
    }

    /**
     * An [InMemoryNode] provides a [MessagingService] that isn't backed by any kind of network or disk storage
     * system, but just uses regular queues on the heap instead. It is intended for unit testing and developer convenience
     * when all entities on 'the network' are being simulated in-process.
     *
     * An instance can be obtained by creating a builder and then using the start method.
     */
    @ThreadSafe
    inner class InMemoryNode(private val manuallyPumped: Boolean, private val handle: Handle): MessagingService {
        inner class Handler(val executor: Executor?, val topic: String,
                            val callback: (Message, MessageHandlerRegistration) -> Unit) : MessageHandlerRegistration
        @Volatile
        protected var running = true
        protected inner class InnerState {
            val handlers: MutableList<Handler> = ArrayList()
            val pendingRedelivery = LinkedList<Message>()
        }
        protected val state = ThreadBox(InnerState())

        override val myAddress: SingleMessageRecipient = handle

        protected val backgroundThread = if (manuallyPumped) null else
            thread(isDaemon = true, name = "In-memory message dispatcher") {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        pumpInternal(true)
                    } catch(e: InterruptedException) {
                        break
                    }
                }
            }

        override fun addMessageHandler(topic: String, executor: Executor?, callback: (Message, MessageHandlerRegistration) -> Unit): MessageHandlerRegistration {
            check(running)
            val (handler, items) = state.locked {
                val handler = Handler(executor, topic, callback).apply { handlers.add(this) }
                val items = ArrayList(pendingRedelivery)
                pendingRedelivery.clear()
                Pair(handler, items)
            }
            for (it in items)
                netSend(it, handle)
            return handler
        }

        override fun removeMessageHandler(registration: MessageHandlerRegistration) {
            check(running)
            state.locked { check(handlers.remove(registration as Handler)) }
        }

        override fun send(message: Message, target: MessageRecipients) {
            check(running)
            netSend(message, target)
        }

        override fun stop() {
            running = false
            if (backgroundThread != null) {
                backgroundThread.interrupt()
                backgroundThread.join()
            }
            netNodeHasShutdown(handle)
        }

        /** Returns the given (topic, data) pair as a newly created message object.*/
        override fun createMessage(topic: String, data: ByteArray): Message {
            return object : Message {
                override val topic: String get() = topic
                override val data: ByteArray get() = data
                override val debugTimestamp: Instant = Instant.now()
                override fun serialise(): ByteArray = this.serialise()
                override val debugMessageID: String get() = serialise().sha256().prefixChars()

                override fun toString() = topic + "#" + String(data)
            }
        }

        /**
         * Delivers a single message from the internal queue. If there are no messages waiting to be delivered and block
         * is true, waits until one has been provided on a different thread via send. If block is false, the return
         * result indicates whether a message was delivered or not.
         */
        fun pump(block: Boolean): Boolean {
            check(manuallyPumped)
            check(running)
            return pumpInternal(block)
        }

        private fun pumpInternal(block: Boolean): Boolean {
            val q = getQueueForHandle(handle)
            val message = (if (block) q.take() else q.poll()) ?: return false

            val deliverTo = state.locked {
                val h = handlers.filter { if (it.topic.isBlank()) true else message.topic == it.topic }

                if (h.isEmpty()) {
                    // Got no handlers for this message yet. Keep the message around and attempt redelivery after a new
                    // handler has been registered. The purpose of this path is to make unit tests that have multi-threading
                    // reliable, as a sender may attempt to send a message to a receiver that hasn't finished setting
                    // up a handler for yet. Most unit tests don't run threaded, but we want to test true parallelism at
                    // least sometimes.
                    pendingRedelivery.add(message)
                    return false
                }

                h
            }

            for (handler in deliverTo) {
                // Now deliver via the requested executor, or on this thread if no executor was provided at registration time.
                (handler.executor ?: MoreExecutors.directExecutor()).execute {
                    try {
                        handler.callback(message, handler)
                    } catch(e: Exception) {
                        loggerFor<InMemoryNetwork>().error("Caught exception in handler for $this/${handler.topic}", e)
                    }
                }
            }

            return true
        }
    }
}
