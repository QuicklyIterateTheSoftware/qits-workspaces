package eu.wohlben.qits.workspaces.control;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * A minimal {@link Instance} over a fixed value (or nothing), for unit tests that construct a bean
 * by hand rather than through CDI.
 *
 * <p>Only the three methods this codebase's optional-port pattern actually calls are implemented —
 * {@code isResolvable()}, {@code get()}, {@code iterator()}. Everything else throws rather than
 * quietly returning a wrong answer, so a future call site that needs more fails loudly here instead
 * of behaving oddly in a test.
 */
public final class StubInstance<T> implements Instance<T> {

  private final T value;

  private StubInstance(T value) {
    this.value = value;
  }

  /** An Instance that resolves to {@code value}. */
  public static <T> Instance<T> of(T value) {
    return new StubInstance<>(value);
  }

  /** An Instance with no bean — the "port not installed" case. */
  public static <T> Instance<T> empty() {
    return new StubInstance<>(null);
  }

  @Override
  public T get() {
    if (value == null) {
      throw new IllegalStateException("no bean; guard with isResolvable()");
    }
    return value;
  }

  @Override
  public boolean isResolvable() {
    return value != null;
  }

  @Override
  public boolean isUnsatisfied() {
    return value == null;
  }

  @Override
  public boolean isAmbiguous() {
    return false;
  }

  @Override
  public Iterator<T> iterator() {
    return (value == null ? Collections.<T>emptyList() : List.of(value)).iterator();
  }

  @Override
  public void destroy(T instance) {
    // nothing to release
  }

  @Override
  public Instance<T> select(Annotation... qualifiers) {
    throw new UnsupportedOperationException("StubInstance does not support select");
  }

  @Override
  public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("StubInstance does not support select");
  }

  @Override
  public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
    throw new UnsupportedOperationException("StubInstance does not support select");
  }

  @Override
  public Handle<T> getHandle() {
    throw new UnsupportedOperationException("StubInstance does not support handles");
  }

  @Override
  public Iterable<? extends Handle<T>> handles() {
    throw new UnsupportedOperationException("StubInstance does not support handles");
  }
}
