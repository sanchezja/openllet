// Copyright (c) 2006 - 2008, Clark & Parsia, LLC. <http://www.clarkparsia.com>
// This source code is available under the terms of the Affero General Public License v3.
//
// Please see LICENSE.txt for full license terms, including the availability of proprietary exceptions.
// Questions, comments, or requests for clarification: licensing@clarkparsia.com

package org.mindswap.pellet.tableau.completion.rule;

import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import openllet.aterm.ATerm;
import openllet.aterm.ATermAppl;
import openllet.aterm.ATermList;
import org.mindswap.pellet.DependencySet;
import org.mindswap.pellet.Edge;
import org.mindswap.pellet.EdgeList;
import org.mindswap.pellet.Individual;
import org.mindswap.pellet.Node;
import org.mindswap.pellet.PelletOptions;
import org.mindswap.pellet.Role;
import org.mindswap.pellet.exceptions.InternalReasonerException;
import org.mindswap.pellet.tableau.completion.CompletionStrategy;
import org.mindswap.pellet.tableau.completion.queue.NodeSelector;
import org.mindswap.pellet.utils.ATermUtils;

/**
 * <p>
 * Title:
 * </p>
 * <p>
 * Description:
 * </p>
 * <p>
 * Copyright: Copyright (c) 2009
 * </p>
 * <p>
 * Company: Clark & Parsia, LLC. <http://www.clarkparsia.com>
 * </p>
 *
 * @author Evren Sirin
 */
public class AllValuesRule extends AbstractTableauRule
{
	public AllValuesRule(final CompletionStrategy strategy)
	{
		super(strategy, NodeSelector.UNIVERSAL, BlockingType.NONE);
	}

	@Override
	public void apply(final Individual x)
	{
		final List<ATermAppl> allValues = x.getTypes(Node.ALL);
		int size = allValues.size();
		Iterator<ATermAppl> i = allValues.iterator();
		while (i.hasNext())
		{
			final ATermAppl av = i.next();
			final DependencySet avDepends = x.getDepends(av);

			if (!PelletOptions.MAINTAIN_COMPLETION_QUEUE && avDepends == null)
				continue;

			applyAllValues(x, av, avDepends);

			if (x.isMerged() || _strategy.getABox().isClosed())
				return;

			// if there are self links through transitive properties restart
			if (size != allValues.size())
			{
				i = allValues.iterator();
				size = allValues.size();
			}
		}
	}

	/**
	 * Apply the allValues rule for the given type with the given dependency. The concept is in the form all(r,C) and this function adds C to all r-neighbors of
	 * x
	 * 
	 * @param x
	 * @param av
	 * @param ds
	 */
	public void applyAllValues(final Individual x, final ATermAppl av, final DependencySet ds)
	{
		// Timer _timer = _kb.timers.startTimer("applyAllValues");

		if (av.getArity() == 0)
			throw new InternalReasonerException();
		final ATerm p = av.getArgument(0);
		final ATermAppl c = (ATermAppl) av.getArgument(1);

		ATermList roleChain = ATermUtils.EMPTY_LIST;
		Role s = null;
		if (p.getType() == ATerm.LIST)
		{
			roleChain = (ATermList) p;
			s = _strategy.getABox().getRole(roleChain.getFirst());
			roleChain = roleChain.getNext();
		}
		else
			s = _strategy.getABox().getRole(p);

		if (s.isTop() && s.isObjectRole())
		{
			applyAllValuesTop(av, c, ds);
			return;
		}

		final EdgeList edges = x.getRNeighborEdges(s);
		for (int e = 0; e < edges.size(); e++)
		{
			final Edge edgeToY = edges.edgeAt(e);
			final Node y = edgeToY.getNeighbor(x);
			final DependencySet finalDS = ds.union(edgeToY.getDepends(), _strategy.getABox().doExplanation());

			if (roleChain.isEmpty())
				applyAllValues(x, s, y, c, finalDS);
			else
				if (y.isIndividual())
				{
					final ATermAppl allRC = ATermUtils.makeAllValues(roleChain, c);

					_strategy.addType(y, allRC, finalDS);
				}

			if (x.isMerged() || _strategy.getABox().isClosed())
				return;
		}

		if (!s.isSimple())
		{
			final Set<ATermList> subRoleChains = s.getSubRoleChains();
			for (final ATermList chain : subRoleChains)
			{
				final DependencySet subChainDS = ds.union(s.getExplainSub(chain), _strategy.getABox().doExplanation());
				if (!applyAllValuesPropertyChain(x, chain, c, subChainDS))
					return;
			}
		}

		if (!roleChain.isEmpty())
			applyAllValuesPropertyChain(x, (ATermList) p, c, ds);

		// _timer.stop();
	}

	protected boolean applyAllValuesPropertyChain(final Individual x, final ATermList chain, final ATermAppl c, final DependencySet ds)
	{
		final Role r = _strategy.getABox().getRole(chain.getFirst());

		final EdgeList edges = x.getRNeighborEdges(r);
		if (!edges.isEmpty())
		{
			final ATermAppl allRC = ATermUtils.makeAllValues(chain.getNext(), c);

			for (int e = 0; e < edges.size(); e++)
			{
				final Edge edgeToY = edges.edgeAt(e);
				final Node y = edgeToY.getNeighbor(x);
				final DependencySet finalDS = ds.union(edgeToY.getDepends(), _strategy.getABox().doExplanation());

				applyAllValues(x, r, y, allRC, finalDS);

				if (x.isMerged() || _strategy.getABox().isClosed())
					return false;
			}
		}

		return true;
	}

	protected void applyAllValues(final Individual subj, final Role pred, final Node obj, final ATermAppl c, final DependencySet ds)
	{
		if (!obj.hasType(c))
		{
			if (_logger.isLoggable(Level.FINE))
				_logger.fine("ALL : " + subj + " -> " + pred + " -> " + obj + " : " + ATermUtils.toString(c) + " - " + ds);

			//because we do not maintain the _queue it could be the case that this _node is pruned, so return
			if (PelletOptions.USE_COMPLETION_QUEUE && !PelletOptions.MAINTAIN_COMPLETION_QUEUE && obj.isPruned())
				return;

			_strategy.addType(obj, c, ds);
		}
	}

	public void applyAllValues(final Individual subj, final Role pred, Node obj, final DependencySet ds)
	{
		final List<ATermAppl> allValues = subj.getTypes(Node.ALL);
		int allValuesSize = allValues.size();
		Iterator<ATermAppl> i = allValues.iterator();
		while (i.hasNext())
		{
			final ATermAppl av = i.next();

			final ATerm p = av.getArgument(0);
			final ATermAppl c = (ATermAppl) av.getArgument(1);

			ATermList roleChain = ATermUtils.EMPTY_LIST;
			Role s = null;
			if (p.getType() == ATerm.LIST)
			{
				roleChain = (ATermList) p;
				s = _strategy.getABox().getRole(roleChain.getFirst());
				roleChain = roleChain.getNext();
			}
			else
				s = _strategy.getABox().getRole(p);

			if (s.isTop() && s.isObjectRole())
			{
				applyAllValuesTop(av, c, ds);
				if (_strategy.getABox().isClosed())
					return;
				continue;
			}

			if (pred.isSubRoleOf(s))
			{
				DependencySet finalDS = subj.getDepends(av);
				finalDS = finalDS.union(ds, _strategy.getABox().doExplanation());
				finalDS = finalDS.union(s.getExplainSubOrInv(pred), _strategy.getABox().doExplanation());
				if (roleChain.isEmpty())
					applyAllValues(subj, s, obj, c, finalDS);
				else
					if (obj.isIndividual())
					{
						final ATermAppl allRC = ATermUtils.makeAllValues(roleChain, c);

						_strategy.addType(obj, allRC, finalDS);
					}

				if (_strategy.getABox().isClosed())
					return;
			}

			if (!s.isSimple())
			{
				final DependencySet finalDS = subj.getDepends(av).union(ds, _strategy.getABox().doExplanation());
				final Set<ATermList> subRoleChains = s.getSubRoleChains();
				for (final ATermList chain : subRoleChains)
				{
					//                    if( !pred.getName().equals( chain.getFirst() ) )
					final Role firstRole = _strategy.getABox().getRole(chain.getFirst());
					if (!pred.isSubRoleOf(firstRole))
						continue;

					final ATermAppl allRC = ATermUtils.makeAllValues(chain.getNext(), c);

					applyAllValues(subj, pred, obj, allRC, finalDS.union(firstRole.getExplainSub(pred.getName()), _strategy.getABox().doExplanation()).union(s.getExplainSub(chain), _strategy.getABox().doExplanation()));

					if (subj.isMerged() || _strategy.getABox().isClosed())
						return;
				}
			}

			if (subj.isMerged())
				return;

			obj = obj.getSame();

			// if there are self links then restart
			if (allValuesSize != allValues.size())
			{
				i = allValues.iterator();
				allValuesSize = allValues.size();
			}
		}
	}

	/**
	 * Apply all values restriction for the Top object role
	 */
	void applyAllValuesTop(final ATermAppl allTopC, final ATermAppl c, final DependencySet ds)
	{
		for (final Node node : _strategy.getABox().getNodes())
			if (node.isIndividual() && !node.isPruned() && !node.hasType(c))
			{
				node.addType(c, ds);
				node.addType(allTopC, ds);

				if (_strategy.getABox().isClosed())
					break;
			}

	}
}
