/*
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
package com.facebook.presto.execution;

import com.facebook.presto.Session;
import com.facebook.presto.spi.PrestoException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.weakref.jmx.MBeanExporter;
import org.weakref.jmx.ObjectNames;

import javax.annotation.Nullable;
import javax.annotation.PreDestroy;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import static com.facebook.presto.SystemSessionProperties.BIG_QUERY;
import static com.facebook.presto.execution.QueuedExecution.createQueuedExecution;
import static com.facebook.presto.spi.StandardErrorCode.USER_ERROR;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@ThreadSafe
public class SqlQueryQueueManager
        implements QueryQueueManager
{
    private final ConcurrentMap<String, QueryQueue> queryQueues = new ConcurrentHashMap<>();
    private final List<QueryQueueRule> rules;
    private final MBeanExporter mbeanExporter;

    @Inject
    public SqlQueryQueueManager(QueryManagerConfig config, ObjectMapper mapper, MBeanExporter mbeanExporter)
    {
        checkNotNull(config, "config is null");
        this.mbeanExporter = checkNotNull(mbeanExporter, "mbeanExporter is null");

        ImmutableList.Builder<QueryQueueRule> rules = ImmutableList.builder();
        if (config.getQueueConfigFile() == null) {
            QueryQueueDefinition global = new QueryQueueDefinition("global", config.getMaxConcurrentQueries(), config.getMaxQueuedQueries());
            QueryQueueDefinition big = new QueryQueueDefinition("big", config.getMaxConcurrentBigQueries(), config.getMaxQueuedBigQueries());
            rules.add(new QueryQueueRule(null, null, ImmutableMap.of(BIG_QUERY, Pattern.compile("true", Pattern.CASE_INSENSITIVE)), ImmutableList.of(big)));
            rules.add(new QueryQueueRule(null, null, ImmutableMap.of(), ImmutableList.of(global)));
        }
        else {
            File file = new File(config.getQueueConfigFile());
            ManagerSpec managerSpec;
            try {
                managerSpec = mapper.readValue(file, ManagerSpec.class);
            }
            catch (IOException e) {
                throw Throwables.propagate(e);
            }
            Map<String, QueryQueueDefinition> definitions = new HashMap<>();
            for (Map.Entry<String, QueueSpec> queue : managerSpec.getQueues().entrySet()) {
                definitions.put(queue.getKey(), new QueryQueueDefinition(queue.getKey(), queue.getValue().getMaxConcurrent(), queue.getValue().getMaxQueued()));
            }

            for (RuleSpec rule : managerSpec.getRules()) {
                rules.add(QueryQueueRule.createRule(rule.getUserRegex(), rule.getSourceRegex(), rule.getSessionPropertyRegexes(), rule.getQueues(), definitions));
            }
        }
        this.rules = rules.build();
    }

    @Override
    public boolean submit(QueryExecution queryExecution, Executor executor, SqlQueryManagerStats stats)
    {
        List<QueryQueue> queues = selectQueues(queryExecution.getQueryInfo().getSession(), executor);

        for (QueryQueue queue : queues) {
            if (!queue.reserve(queryExecution)) {
                // Reject query if we couldn't acquire a permit to enter the queue.
                // The permits will be released when this query fails.
                return false;
            }
        }

        return queues.get(0).enqueue(createQueuedExecution(queryExecution, queues.subList(1, queues.size()), executor, stats));
    }

    // Queues returned have already been created and added queryQueues
    private List<QueryQueue> selectQueues(Session session, Executor executor)
    {
        for (QueryQueueRule rule : rules) {
            List<QueryQueueDefinition> definitions = rule.match(session);
            if (definitions != null) {
                return getOrCreateQueues(session, executor, definitions);
            }
        }
        throw new PrestoException(USER_ERROR, "Query did not match any queuing rule");
    }

    private List<QueryQueue> getOrCreateQueues(Session session, Executor executor, List<QueryQueueDefinition> definitions)
    {
        ImmutableList.Builder<QueryQueue> queues = ImmutableList.builder();
        for (QueryQueueDefinition definition : definitions) {
            String expandedName = definition.getExpandedTemplate(session);
            if (!queryQueues.containsKey(expandedName)) {
                QueryQueue queue = new QueryQueue(executor, definition.getMaxQueued(), definition.getMaxConcurrent());
                if (queryQueues.putIfAbsent(expandedName, queue) == null) {
                    // Export the mbean, after checking for races
                    String objectName = ObjectNames.builder(QueryQueue.class, expandedName).build();
                    mbeanExporter.export(objectName, queue);
                }
            }
            queues.add(queryQueues.get(expandedName));
        }
        return queues.build();
    }

    @PreDestroy
    public void destroy()
    {
        for (String queueName : queryQueues.keySet()) {
            String objectName = ObjectNames.builder(QueryQueue.class, queueName).build();
            mbeanExporter.unexport(objectName);
        }
    }

    public static class ManagerSpec
    {
        private final Map<String, QueueSpec> queues;
        private final List<RuleSpec> rules;

        @JsonCreator
        public ManagerSpec(
                @JsonProperty("queues") Map<String, QueueSpec> queues,
                @JsonProperty("rules") List<RuleSpec> rules)
        {
            this.queues = ImmutableMap.copyOf(checkNotNull(queues, "queues is null"));
            this.rules = ImmutableList.copyOf(checkNotNull(rules, "rules is null"));
        }

        public Map<String, QueueSpec> getQueues()
        {
            return queues;
        }

        public List<RuleSpec> getRules()
        {
            return rules;
        }
    }

    public static class QueueSpec
    {
        private final int maxQueued;
        private final int maxConcurrent;

        @JsonCreator
        public QueueSpec(
                @JsonProperty("maxQueued") int maxQueued,
                @JsonProperty("maxConcurrent") int maxConcurrent)
        {
            this.maxQueued = maxQueued;
            this.maxConcurrent = maxConcurrent;
        }

        public int getMaxQueued()
        {
            return maxQueued;
        }

        public int getMaxConcurrent()
        {
            return maxConcurrent;
        }
    }

    public static class RuleSpec
    {
        @Nullable
        private final Pattern userRegex;
        @Nullable
        private final Pattern sourceRegex;
        private final Map<String, Pattern> sessionPropertyRegexes = new HashMap<>();
        private final List<String> queues;

        @JsonCreator
        public RuleSpec(
                @JsonProperty("user") @Nullable Pattern userRegex,
                @JsonProperty("source") @Nullable Pattern sourceRegex,
                @JsonProperty("queues") List<String> queues)
        {
            this.userRegex = userRegex;
            this.sourceRegex = sourceRegex;
            this.queues = ImmutableList.copyOf(queues);
        }

        @JsonAnySetter
        public void setSessionProperty(String property, Pattern value)
        {
            checkArgument(property.startsWith("session."), "Unrecognized property: %s", property);
            sessionPropertyRegexes.put(property.substring("session.".length(), property.length()), value);
        }

        @Nullable
        public Pattern getUserRegex()
        {
            return userRegex;
        }

        @Nullable
        public Pattern getSourceRegex()
        {
            return sourceRegex;
        }

        public Map<String, Pattern> getSessionPropertyRegexes()
        {
            return ImmutableMap.copyOf(sessionPropertyRegexes);
        }

        public List<String> getQueues()
        {
            return queues;
        }
    }
}
