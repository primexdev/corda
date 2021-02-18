package net.corda.node.internal.rpc.proxies

import net.corda.client.rpc.PermissionException
import net.corda.core.flows.FlowLogic
import net.corda.core.internal.messaging.InternalCordaRPCOps
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.internal.utilities.InvocationHandlerTemplate
import net.corda.core.messaging.FlowHandleWithClientId
import net.corda.node.services.rpc.RpcAuthContext
import net.corda.node.services.rpc.rpcContext
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Implementation of [CordaRPCOps] that checks authorisation.
 */
internal class AuthenticatedRpcOpsProxy(private val delegate: InternalCordaRPCOps) : InternalCordaRPCOps by proxy(delegate, ::rpcContext) {
    /**
     * Returns the RPC protocol version, which is the same the node's Platform Version. Exists since version 1 so guaranteed
     * to be present.
     *
     * TODO: Why is this logic duplicated vs the actual implementation?
     */
    override val protocolVersion: Int get() = delegate.nodeInfo().platformVersion

    // Need overriding to pass additional `listOf(logicType)` argument for polymorphic `startFlow` permissions.
    override fun <T> startFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?) = guard("startFlowDynamic", listOf(logicType), ::rpcContext) {
        delegate.startFlowDynamic(logicType, *args)
    }

    // Need overriding to pass additional `listOf(logicType)` argument for polymorphic `startFlow` permissions.
    override fun <T> startTrackedFlowDynamic(logicType: Class<out FlowLogic<T>>, vararg args: Any?) = guard("startTrackedFlowDynamic", listOf(logicType), ::rpcContext) {
        delegate.startTrackedFlowDynamic(logicType, *args)
    }

    // Need overriding to pass additional `listOf(logicType)` argument for polymorphic `startFlow` permissions.
    @Suppress("SpreadOperator")
    override fun <T> startFlowDynamicWithClientId(
        clientId: String,
        logicType: Class<out FlowLogic<T>>,
        vararg args: Any?
    ): FlowHandleWithClientId<T>  = guard("startFlowDynamicWithClientId", listOf(logicType), ::rpcContext) {
        delegate.startFlowDynamicWithClientId(clientId, logicType, *args)
    }



    private companion object {
        private fun proxy(delegate: InternalCordaRPCOps, context: () -> RpcAuthContext): InternalCordaRPCOps {
            val handler = PermissionsEnforcingInvocationHandler(delegate, context)
            return Proxy.newProxyInstance(delegate::class.java.classLoader, arrayOf(InternalCordaRPCOps::class.java), handler) as InternalCordaRPCOps
        }
    }

    private class PermissionsEnforcingInvocationHandler(override val delegate: InternalCordaRPCOps, private val context: () -> RpcAuthContext) : InvocationHandlerTemplate {
        override fun invoke(proxy: Any, method: Method, arguments: Array<out Any?>?) = guard(method.name, context) { super.invoke(proxy, method, arguments) }
    }
}

private fun <RESULT> guard(methodName: String, context: () -> RpcAuthContext, action: () -> RESULT) = guard(methodName, emptyList(), context, action)

private fun <RESULT> guard(methodName: String, args: List<Class<*>>, context: () -> RpcAuthContext, action: () -> RESULT): RESULT {
    if (!context().isPermitted(methodName, *(args.map(Class<*>::getName).toTypedArray()))) {
        throw PermissionException("User not authorized to perform RPC call $methodName with target $args")
    } else {
        return action()
    }
}