/*
 * Copyright 2015 Google, Inc.
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

package com.netflix.spinnaker.rosco.controllers

import com.netflix.spinnaker.rosco.api.Bake
import com.netflix.spinnaker.rosco.api.BakeOptions
import com.netflix.spinnaker.rosco.api.BakeRequest
import com.netflix.spinnaker.rosco.api.BakeStatus
import com.netflix.spinnaker.rosco.persistence.BakeStore
import com.netflix.spinnaker.rosco.providers.CloudProviderBakeHandler
import com.netflix.spinnaker.rosco.providers.registry.CloudProviderBakeHandlerRegistry
import com.netflix.spinnaker.rosco.jobs.JobExecutor
import com.netflix.spinnaker.rosco.jobs.JobRequest
import groovy.util.logging.Slf4j
import io.swagger.annotations.ApiOperation
import io.swagger.annotations.ApiParam
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@Slf4j
class BakeryController {

  @Autowired
  BakeStore bakeStore

  @Autowired
  JobExecutor jobExecutor

  @Autowired
  CloudProviderBakeHandlerRegistry cloudProviderBakeHandlerRegistry

  @Value('${defaultCloudProviderType:aws}')
  BakeRequest.CloudProviderType defaultCloudProviderType

  @RequestMapping(value = '/bakeOptions', method = RequestMethod.GET)
  List<BakeOptions> bakeOptions() {
    cloudProviderBakeHandlerRegistry.list().collect { it.getBakeOptions() }
  }

  @RequestMapping(value = '/bakeOptions/{cloudProvider}', method = RequestMethod.GET)
  BakeOptions bakeOptionsByCloudProvider(@PathVariable("cloudProvider") BakeRequest.CloudProviderType cloudProvider) {
    def bakeHandler = cloudProviderBakeHandlerRegistry.lookup(cloudProvider)
    if (!bakeHandler) {
      throw new BakeOptions.Exception("Cloud provider $cloudProvider not found")
    }
    return bakeHandler.getBakeOptions()
  }

  @RequestMapping(value = '/bakeOptions/{cloudProvider}/baseImages/{imageId}', method = RequestMethod.GET)
  BakeOptions.BaseImage baseImage(@PathVariable("cloudProvider") BakeRequest.CloudProviderType cloudProvider, @PathVariable("imageId") String imageId) {
    BakeOptions bakeOptions = bakeOptionsByCloudProvider(cloudProvider)
    def baseImage = bakeOptions.baseImages.find { it.id == imageId }
    if (!baseImage) {
      def images = bakeOptions.baseImages*.id.join(", ")
      throw new BakeOptions.Exception("Can't find base image with id ${imageId} in ${cloudProvider} base images: ${images}")
    }
    return baseImage
  }

  @ExceptionHandler(BakeOptions.Exception)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  Map handleBakeOptionsException(BakeOptions.Exception e) {
    [error: "bake.options.not.found", status: HttpStatus.NOT_FOUND, messages: ["Bake options not found. " + e.message]]
  }

  private BakeStatus runBake(String bakeKey, String region, BakeRequest bakeRequest, JobRequest jobRequest) {
    String jobId = jobExecutor.startJob(jobRequest)

    // Give the job jobExecutor some time to kick off the job.
    // The goal here is to fail fast. If it takes too much time, no point in waiting here.
    sleep(1000)

    // Update the status right away so we can fail fast if necessary.
    BakeStatus newBakeStatus = jobExecutor.updateJob(jobId)

    if (newBakeStatus.result == BakeStatus.Result.FAILURE && newBakeStatus.logsContent) {
      throw new IllegalArgumentException(newBakeStatus.logsContent)

      // If we don't have logs content to return here, just let the poller try again on the next iteration.
    }

    // Ok, it didn't fail right away; the bake is underway.
    BakeStatus returnedBakeStatus = bakeStore.storeNewBakeStatus(bakeKey,
                                                                 region,
                                                                 bakeRequest,
                                                                 newBakeStatus,
                                                                 jobRequest.tokenizedCommand.join(" "))

    // Check if the script returned a bake status set by the winner of a race.
    if (returnedBakeStatus.id != newBakeStatus.id) {
      // Kill the new sub-process.
      jobExecutor.cancelJob(newBakeStatus.id)
    }

    return returnedBakeStatus
  }

  @RequestMapping(value = '/api/v1/{region}/bake', method = RequestMethod.POST)
  BakeStatus createBake(@PathVariable("region") String region,
                        @RequestBody BakeRequest bakeRequest,
                        @RequestParam(value = "rebake", defaultValue = "0") String rebake) {
    if (!bakeRequest.cloud_provider_type) {
      bakeRequest = bakeRequest.copyWith(cloud_provider_type: defaultCloudProviderType)
    }

    CloudProviderBakeHandler cloudProviderBakeHandler = cloudProviderBakeHandlerRegistry.lookup(bakeRequest.cloud_provider_type)

    if (cloudProviderBakeHandler) {
      def bakeKey = cloudProviderBakeHandler.produceBakeKey(region, bakeRequest)

      if (rebake == "1") {
        // TODO(duftler): Does it make sense to cancel here as well?
        bakeStore.deleteBakeByKey(bakeKey)
      } else {
        def existingBakeStatus = queryExistingBakes(bakeKey)

        if (existingBakeStatus) {
          return existingBakeStatus
        }
      }

      def packerCommand = cloudProviderBakeHandler.producePackerCommand(region, bakeRequest)
      def jobRequest = new JobRequest(tokenizedCommand: packerCommand)

      if (bakeStore.acquireBakeLock(bakeKey)) {
        return runBake(bakeKey, region, bakeRequest, jobRequest)
      } else {
        def startTime = System.currentTimeMillis()

        // Poll for bake status by bake key every 1/2 second for 5 seconds.
        while (System.currentTimeMillis() - startTime < 5000) {
          def bakeStatus = bakeStore.retrieveBakeStatusByKey(bakeKey)

          if (bakeStatus) {
            return bakeStatus
          } else {
            Thread.sleep(500)
          }
        }

        // Maybe the TTL expired but the bake status wasn't set for some other reason? Let's try again before giving up.
        if (bakeStore.acquireBakeLock(bakeKey)) {
          return runBake(bakeKey, region, bakeRequest, jobRequest)
        }

        throw new IllegalArgumentException("Unable to acquire lock and unable to determine id of lock holder for bake " +
          "key '$bakeKey'.")
      }
    } else {
      throw new IllegalArgumentException("Unknown provider type '$bakeRequest.cloud_provider_type'.")
    }
  }

  @ApiOperation(value = "Look up bake request status")
  @RequestMapping(value = "/api/v1/{region}/status/{statusId}", method = RequestMethod.GET)
  BakeStatus lookupStatus(@ApiParam(value = "The region of the bake request to lookup", required = true) @PathVariable("region") String region,
                          @ApiParam(value = "The id of the bake request to lookup", required = true) @PathVariable("statusId") String statusId) {
    def bakeStatus = bakeStore.retrieveBakeStatusById(statusId)

    if (bakeStatus) {
      return bakeStatus
    }

    throw new IllegalArgumentException("Unable to retrieve status for '$statusId'.")
  }

  @ApiOperation(value = "Look up bake details")
  @RequestMapping(value = "/api/v1/{region}/bake/{bakeId}", method = RequestMethod.GET)
  Bake lookupBake(@ApiParam(value = "The region of the bake to lookup", required = true) @PathVariable("region") String region,
                  @ApiParam(value = "The id of the bake to lookup", required = true) @PathVariable("bakeId") String bakeId) {
    def bake = bakeStore.retrieveBakeDetailsById(bakeId)

    if (bake) {
      return bake
    }

    throw new IllegalArgumentException("Unable to retrieve bake details for '$bakeId'.")
  }

  // TODO(duftler): Synchronize this with existing bakery api.
  @RequestMapping(value = "/api/v1/{region}/logs/{statusId}", method = RequestMethod.GET)
  String lookupLogs(@PathVariable("region") String region,
                    @PathVariable("statusId") String statusId,
                    @RequestParam(value = "html", defaultValue = "false") Boolean html) {
    Map logsContentMap = bakeStore.retrieveBakeLogsById(statusId)

    if (logsContentMap?.logsContent) {
      return html ? "<pre>$logsContentMap.logsContent</pre>" : logsContentMap.logsContent
    }

    throw new IllegalArgumentException("Unable to retrieve logs for '$statusId'.")
  }

  // TODO(duftler): Synchronize this with existing bakery api.
  @RequestMapping(value = '/api/v1/{region}/bake', method = RequestMethod.DELETE)
  String deleteBake(@PathVariable("region") String region,
                    @RequestBody BakeRequest bakeRequest) {
    if (!bakeRequest.cloud_provider_type) {
      bakeRequest = bakeRequest.copyWith(cloud_provider_type: defaultCloudProviderType)
    }

    CloudProviderBakeHandler cloudProviderBakeHandler = cloudProviderBakeHandlerRegistry.lookup(bakeRequest.cloud_provider_type)

    if (cloudProviderBakeHandler) {
      def bakeKey = cloudProviderBakeHandler.produceBakeKey(region, bakeRequest)

      if (bakeStore.deleteBakeByKey(bakeKey)) {
        return "Deleted bake '$bakeKey'."
      }

      throw new IllegalArgumentException("Unable to locate bake with key '$bakeKey'.")
    } else {
      throw new IllegalArgumentException("Unknown provider type '$bakeRequest.cloud_provider_type'.")
    }
  }

  // TODO(duftler): Synchronize this with existing bakery api.
  @ApiOperation(value = "Cancel bake request")
  @RequestMapping(value = "/api/v1/{region}/cancel/{statusId}", method = RequestMethod.GET)
  String cancelBake(@ApiParam(value = "The region of the bake request to cancel", required = true) @PathVariable("region") String region,
                    @ApiParam(value = "The id of the bake request to cancel", required = true) @PathVariable("statusId") String statusId) {
    if (bakeStore.cancelBakeById(statusId)) {
      jobExecutor.cancelJob(statusId)

      return "Canceled bake '$statusId'."
    }

    throw new IllegalArgumentException("Unable to locate incomplete bake with id '$statusId'.")
  }

  private BakeStatus queryExistingBakes(String bakeKey) {
    def bakeStatus = bakeStore.retrieveBakeStatusByKey(bakeKey)

    if (!bakeStatus) {
      return null
    } else if (bakeStatus.state == BakeStatus.State.RUNNING) {
      return bakeStatus
    } else if (bakeStatus.state == BakeStatus.State.COMPLETED && bakeStatus.result == BakeStatus.Result.SUCCESS) {
      return bakeStatus
    } else {
      return null
    }
  }

}
