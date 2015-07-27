/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package kafka.manager

import java.util.concurrent.{LinkedBlockingQueue, TimeUnit, ThreadPoolExecutor}

import akka.pattern._
import akka.util.Timeout
import org.apache.curator.framework.CuratorFramework
import kafka.manager.utils.{AdminUtils, ZkUtils}

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.{Failure, Try}

/**
 * @author hiral
 */

import ActorModel._

case class LogkafkaCommandActorConfig(curator: CuratorFramework, 
                                   longRunningPoolConfig: LongRunningPoolConfig,
                                   askTimeoutMillis: Long = 400, 
                                   version: KafkaVersion)
class LogkafkaCommandActor(logkafkaCommandActorConfig: LogkafkaCommandActorConfig) extends BaseCommandActor with LongRunningPoolActor {

  //private[this] val askTimeout: Timeout = logkafkaCommandActorConfig.askTimeoutMillis.milliseconds

  private[this] val adminUtils = new AdminUtils(logkafkaCommandActorConfig.version)

  @scala.throws[Exception](classOf[Exception])
  override def preStart() = {
    log.info("Started actor %s".format(self.path))
  }

  @scala.throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]) {
    log.error(reason, "Restarting due to [{}] when processing [{}]",
      reason.getMessage, message.getOrElse(""))
    super.preRestart(reason, message)
  }

  @scala.throws[Exception](classOf[Exception])
  override def postStop(): Unit = {
    super.postStop()
  }

  override protected def longRunningPoolConfig: LongRunningPoolConfig = logkafkaCommandActorConfig.longRunningPoolConfig

  override protected def longRunningQueueFull(): Unit = {
    sender ! LKCCommandResult(Try(throw new UnsupportedOperationException("Long running executor blocking queue is full!")))
  }

  override def processActorResponse(response: ActorResponse): Unit = {
    response match {
      case any: Any => log.warning("lkca : processActorResponse : Received unknown message: {}", any)
    }
  }

  override def processCommandRequest(request: CommandRequest): Unit = {
    implicit val ec = longRunningExecutionContext
    request match {
      case LKCDeleteLogkafka(hostname, log_path, logkafkaConfig) =>
        logkafkaCommandActorConfig.version match {
          case Kafka_0_8_1_1 =>
            val result : LKCCommandResult = LKCCommandResult(Failure(new UnsupportedOperationException(
              s"Delete logkafka not supported for kafka version ${logkafkaCommandActorConfig.version}")))
            sender ! result
          case Kafka_0_8_2_0 | Kafka_0_8_2_1 =>
            longRunning {
              Future {
                LKCCommandResult(Try {
                  adminUtils.deleteLogkafka(logkafkaCommandActorConfig.curator, hostname, log_path, logkafkaConfig)
                })
              }
            }
        }
      case LKCCreateLogkafka(hostname, log_path, config, logkafkaConfig) =>
        longRunning {
          Future {
            LKCCommandResult(Try {
              adminUtils.createLogkafka(logkafkaCommandActorConfig.curator, hostname, log_path, config, logkafkaConfig)
            })
          }
        }
      case LKCUpdateLogkafkaConfig(hostname, log_path, config, logkafkaConfig) =>
        longRunning {
          Future {
            LKCCommandResult(Try {
              adminUtils.changeLogkafkaConfig(logkafkaCommandActorConfig.curator, hostname, log_path, config, logkafkaConfig)
            })
          }
        }
      case any: Any => log.warning("lkca : processCommandRequest : Received unknown message: {}", any)
    }
  }
}

