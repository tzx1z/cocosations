package im.conversations.android.xmpp.model.last;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

@XmlElement
public class Query extends Extension {

    public Query() {
        super(Query.class);
    }

    public Query(final long seconds) {
        this();
        this.setAttribute("seconds", seconds);
    }

    /** XEP-0012 и XEP-0256; null, если атрибута нет или он некорректен. */
    public Long getSeconds() {
        final var seconds = this.getAttribute("seconds");
        if (seconds == null) {
            return null;
        }
        try {
            final long value = Long.parseLong(seconds.trim());
            return value < 0 ? null : value;
        } catch (final NumberFormatException e) {
            return null;
        }
    }
}
