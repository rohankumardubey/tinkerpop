/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.tinkerpop.gremlin.process.traversal.strategy.optimization;

import org.apache.tinkerpop.gremlin.process.traversal.Step;
import org.apache.tinkerpop.gremlin.process.traversal.Traversal;
import org.apache.tinkerpop.gremlin.process.traversal.TraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.step.LambdaHolder;
import org.apache.tinkerpop.gremlin.process.traversal.step.Scoping;
import org.apache.tinkerpop.gremlin.process.traversal.step.TraversalParent;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.AndStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.ClassFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.CyclicPathStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.DedupGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.FilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.HasStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.IsStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.NotStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.OrStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.SimplePathStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.TraversalFilterStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WherePredicateStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.filter.WhereTraversalStep;
import org.apache.tinkerpop.gremlin.process.traversal.step.map.OrderGlobalStep;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.AbstractTraversalStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.strategy.decoration.SubgraphStrategy;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalHelper;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * FilterRankingStrategy reorders filter- and order-steps according to their rank. It will also do its best to push
 * step labels as far "right" as possible in order to keep traversers as small and bulkable as possible prior to the
 * absolute need for path-labeling.
 * <p/>
 * <table>
 * <thead>
 * <tr><th>Step</th><th>Rank</th></tr>
 * </thead>
 * <tbody>
 * <tr><td>is(predicate)</td><td>1</td></tr>
 * <tr><td>has(predicate)</td><td>2</td></tr>
 * <tr><td>where(predicate)</td><td>3</td></tr>
 * <tr><td>simplePath()</td><td>4</td></tr>
 * <tr><td>cyclicPath()</td><td>4</td></tr>
 * <tr><td>filter(traversal)</td><td>5</td></tr>
 * <tr><td>not(traversal)</td>td>5</td></tr>
 * <tr><td>where(traversal)</td><td>6</td></tr>
 * <tr><td>or(...)</td><td>7</td></tr>
 * <tr><td>and(...)</td><td>8</td></tr>
 * <tr><td>dedup()</td><td>9</td></tr>
 * <tr><td>order()</td><td>10</td></tr>
 * </tbody>
 * </table>
 *
 * @author Daniel Kuppitz (http://gremlin.guru)
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 * @example <pre>
 * __.order().dedup()                        // is replaced by __.dedup().order()
 * __.dedup().filter(out()).has("value", 0)  // is replaced by __.has("value", 0).filter(out()).dedup()
 * </pre>
 */
public final class FilterRankingStrategy extends AbstractTraversalStrategy<TraversalStrategy.OptimizationStrategy> implements TraversalStrategy.OptimizationStrategy {

    private static final FilterRankingStrategy INSTANCE = new FilterRankingStrategy();
    private static final Set<Class<? extends OptimizationStrategy>> PRIORS = Collections.singleton(IdentityRemovalStrategy.class);

    private FilterRankingStrategy() {
    }

    @Override
    public boolean isApplicable(final Traversal.Admin<?, ?> rootTraversal) {
        return rootTraversal.getStrategies().getStrategy(SubgraphStrategy.class).isPresent() ||
                TraversalHelper.hasStepOfAssignableClassRecursively(FilterStep.class, rootTraversal);
    }

    @Override
    public void apply(final Traversal.Admin<?, ?> traversal) {
        boolean modified = true;
        while (modified) {
            modified = false;
            final List<Step> steps = traversal.getSteps();
            for (int i = 0; i < steps.size() - 1; i++) {
                final Step<?, ?> step = steps.get(i);
                final Step<?, ?> nextStep = step.getNextStep();
                if (!usesLabels(nextStep, step.getLabels())) {
                    final int nextRank = getStepRank(nextStep);
                    if (nextRank != 0) {
                        if (!step.getLabels().isEmpty()) {
                            TraversalHelper.copyLabels(step, nextStep, true);
                            modified = true;
                        }
                        if (getStepRank(step) > nextRank) {
                            traversal.removeStep(nextStep);
                            traversal.addStep(i, nextStep);
                            modified = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * Ranks the given step. Steps with lower ranks can be moved in front of steps with higher ranks. 0 means that
     * the step has no rank and thus is not exchangeable with its neighbors.
     *
     * @param step the step to get a ranking for
     * @return The rank of the given step.
     */
    private static int getStepRank(final Step step) {
        final int rank;
        if (!(step instanceof FilterStep || step instanceof OrderGlobalStep))
            return 0;
        else if (step instanceof IsStep || step instanceof ClassFilterStep)
            rank = 1;
        else if (step instanceof HasStep)
            rank = 2;
        else if (step instanceof WherePredicateStep && ((WherePredicateStep) step).getLocalChildren().isEmpty())
            rank = 3;
        else if (step instanceof SimplePathStep || step instanceof CyclicPathStep)
            rank = 4;
        else if (step instanceof TraversalFilterStep || step instanceof NotStep)
            rank = 5;
        else if (step instanceof WhereTraversalStep)
            rank = 6;
        else if (step instanceof OrStep)
            rank = 7;
        else if (step instanceof AndStep)
            rank = 8;
        else if (step instanceof WherePredicateStep) // has by()-modulation
            rank = 9;
        else if (step instanceof DedupGlobalStep)
            rank = 10;
        else if (step instanceof OrderGlobalStep)
            rank = 11;
        else
            return 0;
        ////////////
        if (step instanceof TraversalParent)
            return getMaxStepRank((TraversalParent) step, rank);
        else
            return rank;
    }

    private static int getMaxStepRank(final TraversalParent parent, final int startRank) {
        int maxStepRank = startRank;
        // no filter steps are global parents (yet)
        for (final Traversal.Admin<?, ?> traversal : parent.getLocalChildren()) {
            for (final Step<?, ?> step : traversal.getSteps()) {
                final int stepRank = getStepRank(step);
                if (stepRank > maxStepRank)
                    maxStepRank = stepRank;
            }
        }
        return maxStepRank;
    }

    private static boolean usesLabels(final Step<?, ?> step, final Set<String> labels) {
        if (step instanceof LambdaHolder)
            return true;
        if (step instanceof Scoping) {
            final Set<String> scopes = ((Scoping) step).getScopeKeys();
            for (final String label : labels) {
                if (scopes.contains(label))
                    return true;
            }
        }
        if (step instanceof TraversalParent) {
            if (TraversalHelper.anyStepRecursively(s -> usesLabels(s, labels), (TraversalParent) step))
                return true;
        }
        return false;
    }

    @Override
    public Set<Class<? extends OptimizationStrategy>> applyPrior() {
        return PRIORS;
    }

    public static FilterRankingStrategy instance() {
        return INSTANCE;
    }
}
