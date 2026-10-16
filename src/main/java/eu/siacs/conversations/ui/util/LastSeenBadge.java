package eu.siacs.conversations.ui.util;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.color.MaterialColors;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.manager.LastActivityManager;
import im.conversations.android.xmpp.model.idle.LastUserInteraction;

/** Метка «в сети» или «N мин назад» с цветной точкой для строк списков контактов и чатов. */
public final class LastSeenBadge {

    private LastSeenBadge() {}

    public static void bind(final TextView view, final Contact contact) {
        if (contact.isBlocked() || contact.isSelf()) {
            view.setVisibility(View.GONE);
            return;
        }
        final boolean online = !contact.getPresences().isEmpty();
        if (!online) {
            // XEP-0012 спрашиваем только для видимых строк; менеджер ограничивает частоту
            contact.getAccount()
                    .getXmppConnection()
                    .getManager(LastActivityManager.class)
                    .request(contact);
        }
        // подключенный, но неактивный контакт (XEP-0319 idle) показывается временем активности
        final var interaction = contact.getLastUserInteraction();
        final int color;
        if (interaction instanceof LastUserInteraction.Idle idle) {
            view.setText(UIHelper.lastSeenAgo(view.getContext(), idle.getSince().toEpochMilli()));
            color = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOutline);
        } else if (online) {
            view.setText(R.string.cocosations_presence_online);
            color = ContextCompat.getColor(view.getContext(), R.color.cocosations_status_ok);
        } else {
            view.setVisibility(View.GONE);
            return;
        }
        TextViewCompat.setCompoundDrawableTintList(view, ColorStateList.valueOf(color));
        view.setVisibility(View.VISIBLE);
    }
}
