package org.sangam.spike.sim;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the faults of one simulator. One-shot faults ({@link #once(Fault)}) are consumed by the
 * first matching request, in FIFO order. Sticky faults ({@link #always(Fault)}) apply to every
 * matching request until {@link #clear()}.
 */
public final class FaultInjector {

    private final ConcurrentLinkedDeque<Fault> once = new ConcurrentLinkedDeque<>();
    private final CopyOnWriteArrayList<Fault> always = new CopyOnWriteArrayList<>();

    public FaultInjector once(Fault fault) {
        once.addLast(fault);
        return this;
    }

    /** Queue the same one-shot fault {@code times} times. */
    public FaultInjector times(int times, Fault fault) {
        for (int i = 0; i < times; i++) {
            once.addLast(fault);
        }
        return this;
    }

    public FaultInjector always(Fault fault) {
        always.add(fault);
        return this;
    }

    public FaultInjector clear() {
        once.clear();
        always.clear();
        return this;
    }

    public int pendingOnce() {
        return once.size();
    }

    /** The faults active for this request: the first matching one-shot fault plus all sticky ones. */
    public synchronized Active next(String routeName) {
        List<Fault> active = new ArrayList<>();
        for (Iterator<Fault> it = once.iterator(); it.hasNext(); ) {
            Fault f = it.next();
            if (f.matches(routeName)) {
                it.remove();
                active.add(f);
                break;
            }
        }
        for (Fault f : always) {
            if (f.matches(routeName)) {
                active.add(f);
            }
        }
        return new Active(List.copyOf(active));
    }

    /** The faults selected for one request, with helpers to apply them. */
    public record Active(List<Fault> faults) {

        public long totalDelayMillis() {
            return faults.stream().filter(f -> f.kind() == Fault.Kind.DELAY).mapToLong(Fault::delayMillis).sum();
        }

        public Fault error() {
            return faults.stream().filter(f -> f.kind() == Fault.Kind.ERROR).findFirst().orElse(null);
        }

        public boolean dropResponse() {
            return faults.stream().anyMatch(f -> f.kind() == Fault.Kind.DROP_RESPONSE);
        }

        public boolean duplicate() {
            return faults.stream().anyMatch(f -> f.kind() == Fault.Kind.DUPLICATE);
        }

        public void sleepDelay() {
            long ms = totalDelayMillis();
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
