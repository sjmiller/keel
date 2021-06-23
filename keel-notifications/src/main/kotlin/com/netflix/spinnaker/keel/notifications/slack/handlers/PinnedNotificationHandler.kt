package com.netflix.spinnaker.keel.notifications.slack.handlers

import com.netflix.spinnaker.config.BaseUrlConfig
import com.netflix.spinnaker.keel.api.NotificationDisplay
import com.netflix.spinnaker.keel.api.NotificationDisplay.*
import com.netflix.spinnaker.keel.notifications.NotificationType
import com.netflix.spinnaker.keel.notifications.slack.SlackPinnedNotification
import com.netflix.spinnaker.keel.notifications.slack.SlackService
import com.slack.api.model.block.LayoutBlock
import com.slack.api.model.kotlin_extension.block.withBlocks
import org.apache.logging.log4j.util.Strings
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Sends notification when pinning an artifact
 */
@Component
class PinnedNotificationHandler(
  private val slackService: SlackService,
  private val gitDataGenerator: GitDataGenerator
) : SlackNotificationHandler<SlackPinnedNotification> {

  override val supportedTypes = listOf(NotificationType.ARTIFACT_PINNED)
  private val log by lazy { LoggerFactory.getLogger(javaClass) }

  private fun SlackPinnedNotification.headerText(): String {
    val env = Strings.toRootUpperCase(pin.targetEnvironment)
    return ":pin: $application ${pinnedArtifact.buildNumber ?: pinnedArtifact.version} is pinned to $env"
  }

  private fun SlackPinnedNotification.compactMessage(): List<LayoutBlock> =
    withBlocks {
      val env = Strings.toRootUpperCase(pin.targetEnvironment)
      val pinnedLink = "<${gitDataGenerator.generateArtifactUrl(application, pinnedArtifact.reference, pinnedArtifact.version)}|#${pinnedArtifact.buildNumber ?: pinnedArtifact.version}>"
      val header = ":pin: *${gitDataGenerator.linkedApp(application)} build $pinnedLink is pinned to $env*"

      section {
        markdownText(header)
      }
    }

  private fun SlackPinnedNotification.normalMessage(): List<LayoutBlock> {
    val env = Strings.toRootUpperCase(pin.targetEnvironment)
    val buildNumberText = when (pinnedArtifact.buildNumber) {
      null -> ""
      else -> " to #${pinnedArtifact.buildNumber}"
    }
    val headerText = "$env is pinned${buildNumberText}"
    val imageUrl = "https://raw.githubusercontent.com/spinnaker/spinnaker.github.io/master/assets/images/md_icons/pinned.png"
    return withBlocks {
      header {
        text(headerText, emoji = true)
      }

      section {
        val olderVersion = "#${currentArtifact.buildMetadata?.number}"
        gitDataGenerator.generateCommitInfo(this,
          application,
          imageUrl,
          pinnedArtifact,
          "pinned",
          olderVersion,
          env)
      }

      val gitMetadata = pinnedArtifact.gitMetadata
      if (gitMetadata != null) {
        gitDataGenerator.conditionallyAddFullCommitMsgButton(this, gitMetadata)
      }
    }
  }

  override fun sendMessage(
    notification: SlackPinnedNotification,
    channel: String,
    notificationDisplay: NotificationDisplay
  ) {
    with(notification) {
      log.debug("Sending pinned artifact notification for application $application")

      val uniqueBlocks = when(notificationDisplay) {
        NORMAL -> notification.normalMessage()
        COMPACT -> notification.compactMessage()
      }

      val username = pin.pinnedBy?.let { slackService.getUsernameByEmail(it) }
      val commonBlocks = withBlocks {
        val gitMetadata = pinnedArtifact.gitMetadata
        if (gitMetadata != null) {
          section {
            gitDataGenerator.generateScmInfo(this,
              application,
              gitMetadata,
              pinnedArtifact)
          }
        }
        context {
          elements {
            markdownText("$username pinned on <!date^${time.epochSecond}^{date_num} {time_secs}|fallback-text-include-PST>: \"${pin.comment}\"")
          }
        }

      }
      slackService.sendSlackNotification(
        channel,
        uniqueBlocks + commonBlocks,
        application = application,
        type = supportedTypes,
        fallbackText = headerText()
      )
    }
  }
}
