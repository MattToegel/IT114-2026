package Project.Common;

public class PointsPayload extends Payload {
    private int points;

    public PointsPayload() {
        // Some Payloads will always/generally have the same type
        // so we can set it in the constructor to reduce repeated code when creating the
        // payload
        setPayloadType(PayloadType.POINTS);
    }

    public PointsPayload(int points) {
        super(); // call default constructor to set payload type
        this.points = points;
    }

    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" Points [=%d]",
                points);
    }
}
