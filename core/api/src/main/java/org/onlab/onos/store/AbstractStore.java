package org.onlab.onos.store;

import org.onlab.onos.event.Event;

import static com.google.common.base.Preconditions.checkState;

/**
 * Base implementation of a store.
 */
public class AbstractStore<E extends Event, D extends StoreDelegate<E>>
        implements Store<E, D> {

    protected D delegate;

    @Override
    public void setDelegate(D delegate) {
        checkState(this.delegate == null || this.delegate == delegate,
                   "Store delegate already set");
        this.delegate = delegate;
    }

    @Override
    public void unsetDelegate(D delegate) {
        if (this.delegate == delegate) {
            this.delegate = null;
        }
    }

    @Override
    public boolean hasDelegate() {
        return delegate != null;
    }

    /**
     * Notifies the delegate with the specified event.
     *
     * @param event event to delegate
     */
    protected void notifyDelegate(E event) {
        if (delegate != null) {
            delegate.notify(event);
        }
    }
}