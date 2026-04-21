package Project.Common;

public class TimerPayload extends Payload {
    private TimerType timerType;
    private int secondsRemaining;

    public TimerType getTimerType() {
        return timerType;
    }

    public void setTimerType(TimerType timerType) {
        this.timerType = timerType;
    }

    public int getSecondsRemaining() {
        return secondsRemaining;
    }

    public void setSecondsRemaining(int secondsRemaining) {
        this.secondsRemaining = secondsRemaining;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" TimerType:[%s] SecondsRemaining:[%d]", timerType, secondsRemaining);
    }
}
