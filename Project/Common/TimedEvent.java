package Project.Common;

/* Originally based off of
https://gist.github.com/MattToegel/c55747f26c5092d6362678d5b1729ec6 */

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Simple countdown timer demo of java.util.Timer facility.
 * Formerly called Countdown.
 */
public class TimedEvent {
    private int secondsRemaining;
    private Runnable expireCallback = null;
    private Consumer<Integer> tickCallback = null;
    private final Timer timer;

    /**
     * Create a TimedEvent to trigger the passed callback after a set duration.
     */
    public TimedEvent(int durationInSeconds, Runnable callback) {
        this(durationInSeconds);
        this.expireCallback = callback;
    }

    /**
     * Create a TimedEvent to trigger after a set duration.
     * Note: Requires expireCallback and/or tickCallback to be set, otherwise it
     * does nothing besides counting down.
     */
    public TimedEvent(int durationInSeconds) {
        timer = new Timer();
        secondsRemaining = durationInSeconds;
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                secondsRemaining--;
                if (tickCallback != null) {
                    tickCallback.accept(secondsRemaining);
                }
                if (secondsRemaining <= 0) {
                    timer.cancel();
                    secondsRemaining = 0;
                    if (expireCallback != null) {
                        expireCallback.run();
                    }
                }
            }
        }, 1000, 1000);
    }

    /**
     * Set a method to be called every timer tick.
     */
    public void setTickCallback(Consumer<Integer> callback) {
        tickCallback = callback;
    }

    /**
     * Set a method to be called when the timer expires.
     */
    public void setExpireCallback(Runnable callback) {
        expireCallback = callback;
    }

    /**
     * Removes all callback references and cancels the timer.
     */
    public void cancel() {
        expireCallback = null;
        tickCallback = null;
        timer.cancel();
    }

    /**
     * Override remaining duration in seconds.
     */
    public void setDurationInSeconds(int durationInSeconds) {
        secondsRemaining = durationInSeconds;
    }

    public int getRemainingTime() {
        return secondsRemaining;
    }

    /**
     * Demo main.
     */
    public static void main(String[] args) {
        TimedEvent event = new TimedEvent(30, () -> System.out.println("Time expired"));
        event.setTickCallback(tick -> System.out.println("Tick: " + tick));
    }
}
