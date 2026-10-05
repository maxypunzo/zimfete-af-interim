package zw.co.zimfete.afs.service;

/** A rule was broken by user input; the message is shown on screen. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}
