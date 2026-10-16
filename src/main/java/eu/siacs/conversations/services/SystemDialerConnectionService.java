package eu.siacs.conversations.services;

import android.telecom.Connection;
import android.telecom.ConnectionRequest;
import android.telecom.DisconnectCause;
import android.telecom.PhoneAccountHandle;

/**
 * Учетная запись звонков системы (не self-managed) для входящих аудиозвонков. Отдельный компонент,
 * чтобы self-managed и обычная учетная запись не принадлежали одному ConnectionService. Исходящие
 * звонки через нее не идут.
 */
public class SystemDialerConnectionService extends CallIntegrationConnectionService {

    @Override
    public Connection onCreateOutgoingConnection(
            final PhoneAccountHandle phoneAccountHandle, final ConnectionRequest request) {
        return Connection.createFailedConnection(
                new DisconnectCause(
                        DisconnectCause.ERROR, "outgoing calls use the self managed account"));
    }
}
