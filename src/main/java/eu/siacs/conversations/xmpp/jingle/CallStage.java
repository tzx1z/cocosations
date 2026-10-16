package eu.siacs.conversations.xmpp.jingle;

/** Этапы установки звонка и состояния сбоя для пошаговой диагностики. */
public enum CallStage {
    CONNECTING_TO_SERVER(0),
    RESOLVING_REMOTE_SERVER(1),
    WAITING_FOR_PEER(2),
    ICE_NEGOTIATION(3),
    CONNECTED(4),
    FAILED_SERVER(0),
    FAILED_FEDERATION(1),
    FAILED_DELIVERY(2),
    FAILED_ICE(3);

    public static final int SEGMENT_COUNT = 4;

    // CONNECTED лежит за последним сегментом: все четыре этапа пройдены
    private final int segment;

    CallStage(final int segment) {
        this.segment = segment;
    }

    public int getSegment() {
        return segment;
    }

    public boolean isFailure() {
        return compareTo(FAILED_SERVER) >= 0;
    }

    public CallStage asFailure() {
        return switch (segment) {
            case 0 -> FAILED_SERVER;
            case 1 -> FAILED_FEDERATION;
            case 2 -> FAILED_DELIVERY;
            default -> FAILED_ICE;
        };
    }
}
