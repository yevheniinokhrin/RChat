package pl.nn44.rchat.protocol.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <pre>
 * Indicates which ChatException reasons (ChatException.Reason) may be returned for given method.
 * </pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.METHOD})
public @interface CeReasons {

    ChatException.Reason[] value();
}
