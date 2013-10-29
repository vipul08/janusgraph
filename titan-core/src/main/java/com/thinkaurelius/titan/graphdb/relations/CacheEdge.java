package com.thinkaurelius.titan.graphdb.relations;

import com.carrotsearch.hppc.cursors.LongObjectCursor;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.thinkaurelius.titan.core.TitanLabel;
import com.thinkaurelius.titan.core.TitanType;
import com.thinkaurelius.titan.diskstorage.keycolumnvalue.Entry;
import com.thinkaurelius.titan.graphdb.internal.ElementLifeCycle;
import com.thinkaurelius.titan.graphdb.internal.InternalRelation;
import com.thinkaurelius.titan.graphdb.internal.InternalVertex;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Matthias Broecheler (me@matthiasb.com)
 */

public class CacheEdge extends AbstractEdge {

    private final byte position;

    public CacheEdge(long id, TitanLabel label, InternalVertex start, InternalVertex end, byte position, Entry data) {
        super(id, label, start, end);
        assert data != null;
        assert position >= 0 && position <= 1;

        this.data = data;
        this.position = position;
    }

    //############## Similar code as CacheProperty but be careful when copying #############################

    private final Entry data;

    @Override
    public InternalRelation it() {
        InternalRelation it = null;
        InternalVertex startVertex = getVertex(0);

        if (startVertex.hasAddedRelations() && startVertex.hasRemovedRelations()) {
            //Test whether this relation has been replaced
            final long id = super.getID();
            Iterable<InternalRelation> previous = startVertex.getAddedRelations(new Predicate<InternalRelation>() {
                @Override
                public boolean apply(@Nullable InternalRelation internalRelation) {
                    return (internalRelation instanceof StandardEdge) && ((StandardEdge) internalRelation).getPreviousID() == id;
                }
            });

            int psize = Iterables.size(previous);
            assert psize == 0 || psize == 1 || (isLoop() && psize == 2);
            it = Iterables.getFirst(previous, null);
        }

        if (it != null)
            return it;

        return super.it();
    }

    private void copyProperties(InternalRelation to) {
        for (LongObjectCursor<Object> entry : getMap()) {
            if (entry.key < 0)
                continue;

            to.setPropertyDirect(tx().getExistingType(entry.key), entry.value);
        }
    }

    private synchronized InternalRelation update() {
        StandardEdge copy = new StandardEdge(super.getID(), getTitanLabel(), getVertex(0), getVertex(1), ElementLifeCycle.Loaded);
        copyProperties(copy);
        copy.remove();

        StandardEdge u = (StandardEdge) tx().addEdge(getVertex(0), getVertex(1), getLabel());
        u.setPreviousID(super.getID());
        copyProperties(u);
        return u;
    }

    @Override
    public long getID() {
        InternalRelation it = it();
        return (it == this) ? super.getID() : it.getID();
    }

    private RelationCache getMap() {
        RelationCache map = data.getCache();
        if (map == null) {
            map = tx().getGraph().getEdgeSerializer().readProperties(getVertex(position), data, tx());
        }
        return map;
    }

    @Override
    public <O> O getPropertyDirect(TitanType type) {
        return getMap().get(type.getID());
    }

    @Override
    public Iterable<TitanType> getPropertyKeysDirect() {
        RelationCache map = getMap();
        List<TitanType> types = new ArrayList<TitanType>(map.size());

        for (LongObjectCursor<Object> entry : map) {
            if (entry.key < 0)
                continue;

            if (entry.value != null)
                types.add(tx().getExistingType(entry.key));
        }

        return types;
    }

    @Override
    public void setPropertyDirect(TitanType type, Object value) {
        update().setPropertyDirect(type, value);
    }

    @Override
    public <O> O removePropertyDirect(TitanType type) {
        return update().removePropertyDirect(type);
    }

    @Override
    public byte getLifeCycle() {
        InternalVertex startVertex = getVertex(0);
        return ((startVertex.hasRemovedRelations() || startVertex.isRemoved()) && tx().isRemovedRelation(super.getID()))
                ? ElementLifeCycle.Removed : ElementLifeCycle.Loaded;
    }

    @Override
    public void remove() {
        verifyRemoval();
        if (!tx().isRemovedRelation(super.getID())) {
            tx().removeRelation(this);
        }
    }

}
