/*
 * Copyright 2018 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.Deferred
import kotlinx.coroutines.experimental.Dispatchers.Unconfined
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.experimental.channels.ReceiveChannel
import kotlinx.coroutines.experimental.channels.consume
import kotlinx.coroutines.experimental.channels.consumeEach
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.channels.toChannel
import kotlinx.coroutines.experimental.selects.whileSelect
import kotlin.coroutines.experimental.CoroutineContext

/**
 * [CoroutineContext] used by [Workflow] operators below.
 *
 * See this module's README for an explanation of why Unconfined is used.
 */
private val operatorContext: CoroutineContext = Unconfined

/**
 * [Transforms][https://stackoverflow.com/questions/15457015/explain-contramap]
 * the receiver to accept events of type [E2] instead of [E1].
 */
fun <S : Any, E2 : Any, E1 : Any, O : Any> Workflow<S, E1, O>.adaptEvents(transform: (E2) -> E1):
    Workflow<S, E2, O> = object : Workflow<S, E2, O>, Deferred<O> by this {
  override fun openSubscriptionToState(): ReceiveChannel<S> =
    this@adaptEvents.openSubscriptionToState()

  override fun sendEvent(event: E2) = this@adaptEvents.sendEvent(transform(event))
}

/**
 * Transforms the receiver to emit states of type [S2] instead of [S1].
 */
fun <S1 : Any, S2 : Any, E : Any, O : Any> Workflow<S1, E, O>.mapState(
  transform: suspend (S1) -> S2
): Workflow<S2, E, O> = object : Workflow<S2, E, O>,
    Deferred<O> by this,
    WorkflowInput<E> by this {
  override fun openSubscriptionToState(): ReceiveChannel<S2> =
    GlobalScope.produce(operatorContext) {
      val source = this@mapState.openSubscriptionToState()
      source.consumeEach {
        send(transform(it))
      }
    }
}

/**
 * Like [mapState], transforms the receiving workflow with [Workflow.state] of type
 * [S1] to one with states of [S2]. Unlike that method, each [S1] update is transformed
 * into a stream of [S2] updates -- useful when an [S1] state might wrap an underlying
 * workflow whose own screens need to be shown.
 */
fun <S1 : Any, S2 : Any, E : Any, O : Any> Workflow<S1, E, O>.switchMapState(
  transform: suspend CoroutineScope.(S1) -> ReceiveChannel<S2>
): Workflow<S2, E, O> = object : Workflow<S2, E, O>,
    Deferred<O> by this,
    WorkflowInput<E> by this {
  override fun openSubscriptionToState(): ReceiveChannel<S2> =
    GlobalScope.produce(operatorContext, capacity = CONFLATED) {
      val upstreamChannel = this@switchMapState.openSubscriptionToState()
      var transformedChannel: ReceiveChannel<S2>? = null
      val downstreamChannel = channel

      coroutineContext[Job]?.invokeOnCompletion { transformedChannel?.cancel(it) }
      upstreamChannel.consume {
        whileSelect {
          upstreamChannel.onReceiveOrNull { upstreamState ->
            if (upstreamState == null) {
              // Upstream channel completed, but we need to finish forwarding the transformed
              // channel.
              transformedChannel?.toChannel(downstreamChannel)
              false
            } else {
              // Stop listening to the old downstream channel and start listening to the new one.
              transformedChannel?.cancel()
              transformedChannel = transform(upstreamState)
              true
            }
          }

          transformedChannel?.onReceiveOrNull?.invoke { transformedState ->
            if (transformedState == null) {
              // Downstream channel completed, continue waiting for upstream state.
              transformedChannel = null
            } else {
              // Forward downstream state.
              downstreamChannel.send(transformedState)
            }
            true
          }
        }
      }
    }
}

/**
 * Transforms the receiver to emit a result of type [O2] instead of [O1].
 */
fun <S : Any, E : Any, O1 : Any, O2 : Any> Workflow<S, E, O1>.mapResult(
  transform: suspend (O1) -> O2
): Workflow<S, E, O2> {
  // We can't just make the downstream a child of the upstream workflow to propagate cancellation,
  // since the downstream's call to `await` would never return (parent waits for all its children
  // to complete).
  val transformedResult = GlobalScope.async(operatorContext) {
    transform(this@mapResult.await())
  }

  // Propagate cancellation upstream.
  transformedResult.invokeOnCompletion { cause ->
    if (cause != null) {
      this@mapResult.cancel(cause)
    }
  }

  return object : Workflow<S, E, O2>,
      Deferred<O2> by transformedResult,
      WorkflowInput<E> by this {
    override fun openSubscriptionToState(): ReceiveChannel<S> =
      this@mapResult.openSubscriptionToState()
  }
}