package com.skala.backend.repair;

import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;
import org.springframework.kafka.annotation.*;
import org.springframework.kafka.core.*;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationSupport;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.RetryableTopic;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableKafkaRetryTopic
class MessagingConfig {
    @Bean org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);scheduler.setThreadNamePrefix("repair-jobs-");return scheduler;
    }
    @Bean NewTopic commandsTopic() { return TopicBuilder.name("diagnosis.commands.v2").partitions(3).replicas(1).build(); }
    @Bean NewTopic eventsTopic() { return TopicBuilder.name("diagnosis.events.v2").partitions(3).replicas(1).build(); }
}
@Component
class ResultConsumer {
    final DiagnosisEvents events;
    ResultConsumer(DiagnosisEvents events) { this.events=events; }
    @RetryableTopic(attempts="4",backOff=@BackOff(delay=1000,multiplier=2),exclude={ApiError.class},numPartitions="3",replicationFactor="1",retryTopicSuffix=".retry",dltTopicSuffix=".dlt")
    @KafkaListener(topics="${app.events-topic}",groupId="repair-results-v2")
    public void result(ConsumerRecord<String,String> record) {
        var e=DiagnosisEvents.parse(record.value());
        if(!e.taskId().toString().equals(record.key())) throw ApiError.bad("PARTITION_KEY");
        events.retryCount(e, retries(record));
        events.receive(record.value());
    }
    static int retries(ConsumerRecord<String,String> record) {
        var header=record.headers().lastHeader("retry_topic-attempts");
        return header==null?0:Math.max(0,java.nio.ByteBuffer.wrap(header.value()).getInt()-1);
    }
    @DltHandler public void dlt(ConsumerRecord<String,String> record) { events.deadLetter(record.value(),Math.max(0,retries(record)-1)); }
}
@Component
class OutboxPublisher {
    final Store db; final KafkaTemplate<String,String> kafka; final TransactionTemplate tx;
    OutboxPublisher(Store db,KafkaTemplate<String,String> kafka,PlatformTransactionManager manager) { this.db=db;this.kafka=kafka;this.tx=new TransactionTemplate(manager); }
    @Scheduled(fixedDelayString="${app.outbox-delay:1000}") public void publish() {
        var rows=tx.execute(s->db.list("UPDATE outbox_event SET lease_until=now()+interval '30 seconds',publish_attempts=publish_attempts+1 WHERE id IN (SELECT id FROM outbox_event WHERE topic IS NOT NULL AND published_at IS NULL AND next_attempt_at<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY created_at LIMIT 10 FOR UPDATE SKIP LOCKED) RETURNING *"));
        if(rows==null) return;
        for(var row:rows) {
            try {
                kafka.send(String.valueOf(row.get("topic")),String.valueOf(row.get("job_id")),Json.write(row.get("payload"))).get(10,TimeUnit.SECONDS);
                db.update("UPDATE outbox_event SET published_at=now(),lease_until=NULL WHERE id=?",row.get("id"));
            } catch(Exception e) {
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                int delay=Math.min(300,1 << Math.min(8,Json.number(row.get("publish_attempts"))));
                db.update("UPDATE outbox_event SET lease_until=NULL,next_attempt_at=now()+(? * interval '1 second') WHERE id=?",delay,row.get("id"));
            }
        }
    }
}

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="app.scheduling.enabled",havingValue="true",matchIfMissing=true)
@EnableScheduling
class SchedulerConfig {}
