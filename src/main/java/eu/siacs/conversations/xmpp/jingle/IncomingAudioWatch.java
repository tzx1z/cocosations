package eu.siacs.conversations.xmpp.jingle;

/**
 * Пропуск входящего звука по счетчику packetsReceived. Критерий тот же, что у пропуска на графике
 * потерь: за интервал не пришло ни одного пакета.
 */
final class IncomingAudioWatch {

    enum Change {
        NONE,
        STOPPED,
        RESUMED
    }

    // голос идет пакетами каждые 20 мс, при DTX в тишине - раз в 400 мс
    static final long STALL_MILLIS = 1000;

    private long packets = 0;
    private long changedAt;
    private boolean stalled = false;

    Change update(final long packetsReceived, final long nowMillis) {
        // при смене SSRC счетчик начинается заново, поэтому поток идет, если значение изменилось
        if (packetsReceived > 0 && packetsReceived != packets) {
            packets = packetsReceived;
            changedAt = nowMillis;
            if (stalled) {
                stalled = false;
                return Change.RESUMED;
            }
            return Change.NONE;
        }
        // до первого пакета следить не за чем: собеседник мог еще не начать передачу
        if (packets > 0 && !stalled && nowMillis - changedAt >= STALL_MILLIS) {
            stalled = true;
            return Change.STOPPED;
        }
        return Change.NONE;
    }

    boolean isStalled() {
        return stalled;
    }
}
