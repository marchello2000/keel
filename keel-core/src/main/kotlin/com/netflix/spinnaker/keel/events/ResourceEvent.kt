/*
 * Copyright 2018 Netflix, Inc.
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
package com.netflix.spinnaker.keel.events

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeInfo.As
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import com.netflix.spinnaker.keel.api.ApiVersion
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceId
import com.netflix.spinnaker.keel.api.application
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.events.ResourceState.Diff
import com.netflix.spinnaker.keel.events.ResourceState.Error
import com.netflix.spinnaker.keel.events.ResourceState.Missing
import com.netflix.spinnaker.keel.events.ResourceState.Ok
import java.time.Clock
import java.time.Instant

// todo emjburns: use the common class in kork, but refactor so you can also set the time in those.
// todo emjburns: maybe only do ^ once the api stabilizes, for now we're using maps in gate.
@JsonTypeInfo(
  use = Id.NAME,
  property = "type",
  include = As.PROPERTY
)
@JsonSubTypes(
  Type(value = ResourceCreated::class, name = "ResourceCreated"),
  Type(value = ResourceUpdated::class, name = "ResourceUpdated"),
  Type(value = ResourceDeleted::class, name = "ResourceDeleted"),
  Type(value = ResourceMissing::class, name = "ResourceMissing"),
  Type(value = ResourceActuationLaunched::class, name = "ResourceActuationLaunched"),
  Type(value = ResourceDeltaDetected::class, name = "ResourceDeltaDetected"),
  Type(value = ResourceDeltaResolved::class, name = "ResourceDeltaResolved"),
  Type(value = ResourceValid::class, name = "ResourceValid"),
  Type(value = ResourceCheckError::class, name = "ResourceCheckError")
)
sealed class ResourceEvent {
  abstract val apiVersion: ApiVersion
  abstract val kind: String
  abstract val id: String // TODO: should be ResourceId but Jackson can't handle inline classes
  abstract val application: String
  abstract val timestamp: Instant

  val resourceId: ResourceId
    @JsonIgnore
    get() = ResourceId(id)

  /**
   * Should the event be recorded in a resource's history?
   */
  @JsonIgnore
  open val ignoreInHistory: Boolean = false

  /**
   * Should repeated events of the same type
   */
  @JsonIgnore
  open val ignoreRepeatedInHistory: Boolean = false

  companion object {
    val clock: Clock = Clock.systemDefaultZone()
  }
}

/**
 * A new resource was registered for management.
 */
data class ResourceCreated(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  override val timestamp: Instant
) : ResourceEvent() {

  constructor(resource: Resource<*>, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    clock.instant()
  )
}

/**
 * The desired state of a resource was updated.
 *
 * @property delta The difference between the "base" spec (previous version) and "working" spec (the
 * updated version).
 */
data class ResourceUpdated(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  val delta: Map<String, Any?>,
  override val timestamp: Instant
) : ResourceEvent() {
  constructor(resource: Resource<*>, delta: Map<String, Any?>, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    delta,
    clock.instant()
  )
}

data class ResourceDeleted(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  override val timestamp: Instant
) : ResourceEvent() {
  constructor(resource: Resource<*>, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    clock.instant()
  )
}

abstract class ResourceCheckResult : ResourceEvent() {
  abstract val state: ResourceState

  @JsonIgnore
  override val ignoreRepeatedInHistory = true
}

/**
 * A managed resource does not currently exist in the cloud.
 */
data class ResourceMissing(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  override val timestamp: Instant
) : ResourceCheckResult() {
  @JsonIgnore
  override val state = Missing

  constructor(resource: Resource<*>, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    clock.instant()
  )
}

/**
 * A difference between the desired and actual state of a managed resource was detected.
 *
 * @property delta The difference between the "base" spec (desired) and "working" spec (actual).
 */
data class ResourceDeltaDetected(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  val delta: Map<String, Any?>,
  override val timestamp: Instant
) : ResourceCheckResult() {
  @JsonIgnore
  override val state = Diff

  constructor(resource: Resource<*>, delta: Map<String, Any?>, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    delta,
    clock.instant()
  )
}

/**
 * A task or tasks were launched to resolve a mismatch between desired and actual state of a managed
 * resource.
 */
data class ResourceActuationLaunched(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  val plugin: String,
  val tasks: List<Task>,
  override val timestamp: Instant
) : ResourceEvent() {
  constructor(resource: Resource<*>, plugin: String, tasks: List<Task>, clock: Clock = Companion.clock) :
    this(
      resource.apiVersion,
      resource.kind,
      resource.id.value,
      resource.application,
      plugin,
      tasks,
      clock.instant()
    )
}

/**
 * The desired and actual states of a managed resource now match where previously there was a delta
 * (or the resource did not exist).
 */
data class ResourceDeltaResolved(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  override val timestamp: Instant,
  val desired: Any,
  val current: Any
) : ResourceCheckResult() {
  @JsonIgnore
  override val state = Ok

  constructor(resource: Resource<*>, current: Any, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    clock.instant(),
    resource.spec,
    current
  )
}

data class ResourceValid(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  override val timestamp: Instant
) : ResourceCheckResult() {
  @JsonIgnore
  override val state = Ok

  @JsonIgnore
  override val ignoreInHistory = true

  constructor(resource: Resource<*>, clock: Clock = Companion.clock) :
    this(
      resource.apiVersion,
      resource.kind,
      resource.id.value,
      resource.application,
      clock.instant()
    )
}

data class ResourceCheckError(
  override val apiVersion: ApiVersion,
  override val kind: String,
  override val id: String,
  override val application: String,
  override val timestamp: Instant,
  val exceptionType: Class<Throwable>,
  val exceptionMessage: String?
) : ResourceCheckResult() {
  @JsonIgnore
  override val state = Error

  constructor(resource: Resource<*>, exception: Throwable, clock: Clock = Companion.clock) : this(
    resource.apiVersion,
    resource.kind,
    resource.id.value,
    resource.application,
    clock.instant(),
    exception.javaClass,
    exception.message
  )
}

/**
 * The reference to a task launched (currently always in Orca) to resolve a difference between the
 * desired and actual states of a managed resource.
 */
@JsonSerialize(using = ToStringSerializer::class)
@JsonDeserialize(using = TaskRefDeserializer::class)
data class TaskRef(val value: String) {
  override fun toString(): String = value
}

data class Task(
  val id: String,
  val name: String
)

class TaskRefDeserializer : StdDeserializer<TaskRef>(TaskRef::class.java) {
  override fun deserialize(parser: JsonParser, context: DeserializationContext): TaskRef =
    TaskRef(parser.valueAsString)
}
