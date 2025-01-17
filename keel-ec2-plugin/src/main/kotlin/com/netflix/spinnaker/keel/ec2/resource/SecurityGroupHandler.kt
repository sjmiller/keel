/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.keel.ec2.resource

import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceDiff
import com.netflix.spinnaker.keel.api.SimpleLocations
import com.netflix.spinnaker.keel.api.SimpleRegionSpec
import com.netflix.spinnaker.keel.api.actuation.Job
import com.netflix.spinnaker.keel.api.actuation.Task
import com.netflix.spinnaker.keel.api.actuation.TaskLauncher
import com.netflix.spinnaker.keel.api.ec2.AllPorts
import com.netflix.spinnaker.keel.api.ec2.CidrRule
import com.netflix.spinnaker.keel.api.ec2.CrossAccountReferenceRule
import com.netflix.spinnaker.keel.api.ec2.EC2_CLOUD_PROVIDER
import com.netflix.spinnaker.keel.api.ec2.EC2_SECURITY_GROUP_V1
import com.netflix.spinnaker.keel.api.ec2.IngressPorts
import com.netflix.spinnaker.keel.api.ec2.PortRange
import com.netflix.spinnaker.keel.api.ec2.ReferenceRule
import com.netflix.spinnaker.keel.api.ec2.SecurityGroup
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupOverride
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupRule
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupRule.Protocol
import com.netflix.spinnaker.keel.api.ec2.SecurityGroupSpec
import com.netflix.spinnaker.keel.api.plugins.ResolvableResourceHandler
import com.netflix.spinnaker.keel.api.plugins.Resolver
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ResourceNotFound
import com.netflix.spinnaker.keel.clouddriver.model.SecurityGroupModel
import com.netflix.spinnaker.keel.core.name
import com.netflix.spinnaker.keel.diff.DefaultResourceDiff
import com.netflix.spinnaker.keel.diff.toIndividualDiffs
import com.netflix.spinnaker.keel.model.OrcaJob
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.retrofit.isNotFound
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import retrofit2.HttpException

open class SecurityGroupHandler(
  open val cloudDriverService: CloudDriverService,
  val cloudDriverCache: CloudDriverCache,
  val orcaService: OrcaService,
  val taskLauncher: TaskLauncher,
  resolvers: List<Resolver<*>>
) : ResolvableResourceHandler<SecurityGroupSpec, Map<String, SecurityGroup>>(resolvers) {

  override val supportedKind = EC2_SECURITY_GROUP_V1

  override suspend fun toResolvedType(resource: Resource<SecurityGroupSpec>): Map<String, SecurityGroup> =
    with(resource.spec) {
      locations.regions.map { region ->
        region.name to SecurityGroup(
          moniker = Moniker(app = moniker.app, stack = moniker.stack, detail = moniker.detail),
          location = SecurityGroup.Location(
            account = locations.account,
            vpc = locations.vpc ?: error("No vpc supplied or resolved"),
            region = region.name
          ),
          description = overrides[region.name]?.description ?: description,
          inboundRules = (overrides[region.name]?.inboundRules ?: emptySet()) + inboundRules
        )
      }.toMap()
    }

  override suspend fun current(resource: Resource<SecurityGroupSpec>): Map<String, SecurityGroup> =
    cloudDriverService.getSecurityGroup(resource.spec, resource.serviceAccount)

  override suspend fun upsert(
    resource: Resource<SecurityGroupSpec>,
    resourceDiff: ResourceDiff<Map<String, SecurityGroup>>
  ): List<Task> =
    coroutineScope {
      resourceDiff
        .toIndividualDiffs()
        .filter { diff -> diff.hasChanges() }
        .map { diff ->
          val spec = diff.desired
          val job: Job
          val verb: Pair<String, String>

          when (diff.current) {
            null -> {
              job = generateCreateJob(spec)
              verb = Pair("Create", "create")
            }
            else -> {
              job = generateUpdateJob(spec)
              verb = Pair("Update", "update")
            }
          }

          log.info("${verb.first} security group using task: $job")

          async {
            taskLauncher.submitJob(
              resource = resource,
              description = "${verb.first} security group ${spec.moniker} in ${spec.location.account}/${spec.location.region}",
              correlationId = "${resource.id}:${spec.location.region}",
              job = job
            )
          }
        }
        .map { it.await() }
    }

  override suspend fun actuationInProgress(resource: Resource<SecurityGroupSpec>): Boolean =
    resource
      .spec
      .locations
      .regions
      .map { it.name }
      .any { region ->
        orcaService
          .getCorrelatedExecutions("${resource.id}:$region")
          .isNotEmpty()
      }

  override suspend fun export(exportable: Exportable): SecurityGroupSpec {
    val securityGroups = getSecurityGroupsByRegion(exportable)

    if (securityGroups.isEmpty()) {
      throw ResourceNotFound(
        "Could not find security group: ${exportable.moniker} " +
          "in account: ${exportable.account}"
      )
    }

    val base = securityGroups.values.minByOrNull { it.inboundRules.size }!!
    val spec = SecurityGroupSpec(
      moniker = base.moniker,
      locations = SimpleLocations(
        account = exportable.account,
        vpc = base.location.vpc,
        regions = securityGroups.keys.map {
          SimpleRegionSpec(it)
        }
          .toSet()
      ),
      description = base.description,
      inboundRules = base.inboundRules,
      overrides = mutableMapOf()
    )

    spec.generateOverrides(securityGroups)

    return spec
  }

  override suspend fun delete(resource: Resource<SecurityGroupSpec>): List<Task> {
    val currentState = current(resource)
    val regions = currentState.keys
    with(resource.spec.locations) {
      val stages = regions.map { region ->
        val vpc = cloudDriverCache.networkBy(vpc!!, account, region)
        mapOf(
          "type" to "deleteSecurityGroup",
          "securityGroupName" to resource.name,
          // This is a misnomer: you can only pass a single region since it needs to match the vpcID below.  ¯\_(ツ)_/¯
          "regions" to listOf(region),
          "credentials" to account,
          "vpcId" to vpc.id,
          "user" to resource.serviceAccount,
        )
      }
      return if (stages.isEmpty()) {
        emptyList()
      } else {
        listOf(
          taskLauncher.submitJob(
            resource = resource,
            description = "Delete security group ${resource.name} in account $account (${regions.joinToString()})",
            correlationId = "${resource.id}:delete",
            stages = stages
          )
        )
      }
    }
  }

  private fun SecurityGroupSpec.generateOverrides(
    regionalGroups: Map<String, SecurityGroup>
  ) =
    regionalGroups.forEach { (region, securityGroup) ->
      val inboundDiff =
        DefaultResourceDiff(securityGroup.inboundRules, this.inboundRules)
          .hasChanges()
      val vpcDiff = securityGroup.location.vpc != this.locations.vpc
      val descriptionDiff = securityGroup.description != this.description

      if (inboundDiff || vpcDiff || descriptionDiff) {
        (this.overrides as MutableMap)[region] = SecurityGroupOverride(
          vpc = if (vpcDiff) {
            securityGroup.location.vpc
          } else {
            null
          },
          description = if (descriptionDiff) {
            securityGroup.description
          } else {
            null
          },
          inboundRules = if (inboundDiff) {
            securityGroup.inboundRules
          } else {
            null
          }
        )
      }
    }

  /**
   * Returns a map of region names to the corresponding [SecurityGroup] objects to be used in [export].
   */
  protected open suspend fun getSecurityGroupsByRegion(exportable: Exportable): Map<String, SecurityGroup> {
    val summaries = exportable.regions.associateWith { region ->
      try {
        cloudDriverCache.securityGroupByName(
          account = exportable.account,
          region = region,
          name = exportable.moniker.toString()
        )
      } catch (e: ResourceNotFound) {
        null
      }
    }
      .filterValues { it != null }

    return coroutineScope {
      summaries.map { (region, summary) ->
        async {
          try {
            cloudDriverService.getSecurityGroup(
              exportable.user,
              exportable.account,
              EC2_CLOUD_PROVIDER,
              summary!!.name,
              region,
              summary.vpcId
            )
              .toSecurityGroup()
          } catch (e: HttpException) {
            if (e.isNotFound) {
              null
            } else {
              throw e
            }
          }
        }
      }
        .mapNotNull { it.await() }
        .associateBy { it.location.region }
    }
  }

  private suspend fun CloudDriverService.getSecurityGroup(
    spec: SecurityGroupSpec,
    serviceAccount: String
  ): Map<String, SecurityGroup> =
    coroutineScope {
      spec.locations.regions.map { region ->
        async {
          try {
            getSecurityGroup(
              serviceAccount,
              spec.locations.account,
              EC2_CLOUD_PROVIDER,
              spec.moniker.toString(),
              region.name,
              cloudDriverCache.networkBy(spec.locations.vpc, spec.locations.account, region.name).id
            )
              .toSecurityGroup()
          } catch (e: HttpException) {
            if (e.isNotFound) {
              null
            } else {
              throw e
            }
          }
        }
      }
        .mapNotNull { it.await() }
        .associateBy { it.location.region }
    }

  private fun SecurityGroupModel.toSecurityGroup() =
    SecurityGroup(
      moniker = Moniker(app = moniker.app, stack = moniker.stack, detail = moniker.detail),
      location = SecurityGroup.Location(
        account = accountName,
        vpc = vpcId?.let { cloudDriverCache.networkBy(it).name }
          ?: error("Only security groups in a VPC are supported"),
        region = region
      ),
      description = description,
      tags = tags,
      inboundRules = inboundRules.flatMap { rule ->
        val ingressGroup = rule.securityGroup
        val ingressRange = rule.range
        val protocol = rule.protocol!!.clouddriverProtocolToKeel()
        when {
          ingressGroup?.name != null ->
            rule.portRanges
              ?.map { it.toPortRange() }
              ?.map { portRange ->
                when {
                  ingressGroup.accountName != accountName || ingressGroup.vpcId != vpcId -> CrossAccountReferenceRule(
                    protocol,
                    ingressGroup.name!!,
                    ingressGroup.accountName!!,
                    cloudDriverCache.networkBy(ingressGroup.vpcId!!).name!!,
                    portRange
                  )
                  else -> ReferenceRule(
                    protocol,
                    ingressGroup.name!!,
                    portRange
                  )
                }
              } ?: emptyList()
          ingressRange != null ->
            rule.portRanges
              ?.map { it.toPortRange() }
              ?.map { portRange ->
                CidrRule(
                  protocol,
                  portRange,
                  ingressRange.ip + ingressRange.cidr,
                  rule.description
                )
              } ?: emptyList()
          ingressGroup != null && ingressGroup.name == null -> {
            /**
             * Due to edge cases in AWS, it is possible for an inbound rule to reference a security group
             * that no longer exists. When this happens, the clouddriver response won't have a [name]
             * field. We silently ignore an inbound rule with a dangling ref, since it has no effect.
             */
            log.warn("security group $name ($accountName, $region) has inbound rule that references non-existent security group ${ingressGroup.id} (${ingressGroup.accountName}, ${ingressGroup.region}, ${ingressGroup.vpcId})")
            emptyList()
          }
          else -> emptyList()
        }
      }
        .toSet()
    )

  private fun SecurityGroupModel.SecurityGroupRulePortRange.toPortRange(): IngressPorts =
    (startPort to endPort).let { (start, end) ->
      if (start == null || end == null) AllPorts else PortRange(start, end)
    }

  private fun String.clouddriverProtocolToKeel(): Protocol =
    if (this == "-1") Protocol.ALL else Protocol.valueOf(toUpperCase())

  protected open fun generateCreateJob(securityGroup: SecurityGroup): Job =
    with(securityGroup) {
      OrcaJob(
        "upsertSecurityGroup",
        mapOf(
          "application" to moniker.app,
          "credentials" to location.account,
          "cloudProvider" to EC2_CLOUD_PROVIDER,
          "name" to moniker.toString(),
          "regions" to listOf(location.region),
          "vpcId" to cloudDriverCache.networkBy(location.vpc, location.account, location.region).id,
          "description" to description,
          "securityGroupIngress" to inboundRules
            // we have to do a 2-phase create for self-referencing ingress rules as the referenced
            // security group must exist prior to the rule being applied. We filter then out here and
            // the subsequent diff will apply the additional group(s).
            .filterNot { it is ReferenceRule && it.name == moniker.name }
            .mapNotNull {
              it.referenceRuleToJob(this)
            },
          "ipIngress" to inboundRules.mapNotNull {
            it.cidrRuleToJob()
          },
          "accountName" to location.account
        )
      )
    }

  protected open fun generateUpdateJob(securityGroup: SecurityGroup): Job =
    with(securityGroup) {
      OrcaJob(
        "upsertSecurityGroup",
        mapOf(
          "application" to moniker.app,
          "credentials" to location.account,
          "cloudProvider" to EC2_CLOUD_PROVIDER,
          "name" to moniker.toString(),
          "regions" to listOf(location.region),
          "vpcId" to cloudDriverCache.networkBy(location.vpc, location.account, location.region).id,
          "description" to description,
          "securityGroupIngress" to inboundRules.mapNotNull {
            it.referenceRuleToJob(this)
          },
          "ipIngress" to inboundRules.mapNotNull {
            it.cidrRuleToJob()
          },
          "accountName" to location.account
        )
      )
    }

  private fun SecurityGroupRule.referenceRuleToJob(securityGroup: SecurityGroup): Map<String, Any?>? =
    when (this) {
      is ReferenceRule -> mapOf(
        "type" to protocol.type,
        "startPort" to (portRange as? PortRange)?.startPort,
        "endPort" to (portRange as? PortRange)?.endPort,
        "name" to name
      )
      is CrossAccountReferenceRule -> mapOf(
        "type" to protocol.type,
        "startPort" to (portRange as? PortRange)?.startPort,
        "endPort" to (portRange as? PortRange)?.endPort,
        "name" to name,
        "accountName" to account,
        "crossAccountEnabled" to true,
        "vpcId" to cloudDriverCache.networkBy(
          vpc,
          account,
          securityGroup.location.region
        ).id
      )
      else -> null
    }

  private fun SecurityGroupRule.cidrRuleToJob(): Map<String, Any?>? =
    when (this) {
      is CidrRule ->
        mapOf<String, Any?>(
          "type" to protocol.type,
          "startPort" to (portRange as? PortRange)?.startPort,
          "endPort" to (portRange as? PortRange)?.endPort,
          "cidr" to blockRange
        )
      else -> null
    }

  private val Protocol.type: String
    get() = if (this == Protocol.ALL) "-1" else name.toLowerCase()
}
