package eu.siacs.conversations.ui.fragment.settings;

import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import eu.siacs.conversations.AppSettings;
import eu.siacs.conversations.R;

public class PrivacySettingsFragment extends XmppPreferenceFragment {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferencesFromResource(R.xml.preferences_privacy, rootKey);
        final ListPreference reportsLimit = findPreference(AppSettings.CALL_REPORTS_LIMIT);
        if (reportsLimit != null) {
            reportsLimit.setSummaryProvider(
                    preference ->
                            getString(
                                    R.string.cocosations_pref_call_reports_limit_summary,
                                    new AppSettings(requireContext()).getCallReportsLimit()));
        }
    }

    @Override
    protected void onSharedPreferenceChanged(@NonNull String key) {
        super.onSharedPreferenceChanged(key);
        switch (key) {
            case AppSettings.READ_RECEIPTS,
                    AppSettings.BROADCAST_LAST_ACTIVITY,
                    AppSettings.ALLOW_MESSAGE_CORRECTION ->
                    requireService().refreshAllPresences();
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        requireActivity().setTitle(R.string.pref_privacy);
    }
}
