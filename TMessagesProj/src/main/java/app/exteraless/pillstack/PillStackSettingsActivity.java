package app.exteraless.pillstack;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.LocationActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

import tw.nekomimi.nekogram.settings.BaseNekoSettingsActivity;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

/**
 * Экран настроек Pill Stack: какие пилюли показывать, в каком порядке,
 * плюс параметры курсов и погоды.
 *
 * Полное имя класса — часть контракта с общим экраном настроек, не переименовывать.
 */
public class PillStackSettingsActivity extends BaseNekoSettingsActivity {

    private final ArrayList<Integer> activePills = new ArrayList<>();
    private final ArrayList<Integer> hiddenPills = new ArrayList<>();

    private int infiniteScrollingRow;
    private int scrollingDividerRow;

    private int activeHeaderRow;
    private int activeStartRow;
    private int activeEndRow;
    private int activeEmptyRow;
    private int activeDividerRow;

    private int hiddenHeaderRow;
    private int hiddenStartRow;
    private int hiddenEndRow;
    private int hiddenEmptyRow;
    private int addRateRow;
    private int hiddenInfoRow;

    private int weatherRow;
    private int weatherDividerRow;

    private int resetRow;
    private int resetDividerRow;

    public PillStackSettingsActivity() {
        super();
        PillStackConfig.init();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.PillStackTitle);
    }

    @Override
    public int getSearchGuid() {
        return 25000;
    }

    @Override
    public int getSearchIcon() {
        return R.drawable.msg_pin;
    }

    @Override
    public String getSearchPrefix() {
        return "PillStack";
    }

    @Override
    protected String getKey() {
        return "pillstack";
    }

    @Override
    protected void updateRows() {
        super.updateRows();

        PillStackConfig.sanitizePills();
        activePills.clear();
        activePills.addAll(PillStackConfig.getActivePills());
        hiddenPills.clear();
        hiddenPills.addAll(PillStackConfig.getHiddenPills());

        infiniteScrollingRow = addRow("infiniteScrolling");
        scrollingDividerRow = addRow();

        activeHeaderRow = addRow("activePills");
        if (activePills.isEmpty()) {
            activeStartRow = activeEndRow = -1;
            activeEmptyRow = addRow();
        } else {
            activeEmptyRow = -1;
            activeStartRow = rowCount;
            rowCount += activePills.size();
            activeEndRow = rowCount;
        }
        activeDividerRow = addRow();

        hiddenHeaderRow = addRow("hiddenPills");
        if (hiddenPills.isEmpty()) {
            hiddenStartRow = hiddenEndRow = -1;
            hiddenEmptyRow = addRow();
        } else {
            hiddenEmptyRow = -1;
            hiddenStartRow = rowCount;
            rowCount += hiddenPills.size();
            hiddenEndRow = rowCount;
        }
        addRateRow = RateInstances.canAddMore() ? addRow("addRate") : -1;
        hiddenInfoRow = addRow();

        if (activePills.contains(PillType.WEATHER.id)) {
            // сами настройки погоды живут на отдельном экране (как в exteraGram)
            weatherRow = addRow("weather");
            weatherDividerRow = addRow();
        } else {
            weatherRow = weatherDividerRow = -1;
        }

        resetRow = addRow("reset");
        resetDividerRow = addRow();
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        new ItemTouchHelper(new ReorderCallback()).attachToRecyclerView(listView);
        return view;
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private void saveLayout() {
        PillStackConfig.saveLayout(activePills, hiddenPills);
        PillStackEvents.notifyLayoutChanged();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == infiniteScrollingRow) {
            boolean value = PillStackConfig.infiniteScrolling.toggleConfigBool();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            return;
        }
        if (weatherRow != -1 && position == weatherRow) {
            presentFragment(new app.exteraless.pillstack.pills.weather.WeatherSettingsActivity());
            return;
        }
        if (addRateRow != -1 && position == addRateRow) {
            RateInstances.Instance created = RateInstances.create(RateInstances.defaultBase(), PillCurrencies.AUTO);
            if (created == null) {
                return;
            }
            hiddenPills.remove(Integer.valueOf(created.id));
            if (!activePills.contains(created.id)) {
                activePills.add(created.id);
            }
            saveLayout();
            updateRowsAndNotify();
            PillStackEvents.notifyLayoutChanged();
            return;
        }
        if (position == resetRow) {
            PillStackConfig.resetLayout();
            updateRowsAndNotify();
            PillStackEvents.notifyLayoutChanged();
            BulletinFactory.of(this).createSimpleBulletin(R.raw.done, getString(R.string.PillStackResetDone)).show();
            return;
        }
        if (activeStartRow != -1 && position >= activeStartRow && position < activeEndRow) {
            int id = activePills.get(position - activeStartRow);
            activePills.remove(Integer.valueOf(id));
            hiddenPills.add(id);
            saveLayout();
            updateRowsAndNotify();
            return;
        }
        if (hiddenStartRow != -1 && position >= hiddenStartRow && position < hiddenEndRow) {
            int id = hiddenPills.get(position - hiddenStartRow);
            hiddenPills.remove(Integer.valueOf(id));
            activePills.add(id);
            saveLayout();
            updateRowsAndNotify();
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        int id = -1;
        if (activeStartRow != -1 && position >= activeStartRow && position < activeEndRow) {
            id = activePills.get(position - activeStartRow);
        } else if (hiddenStartRow != -1 && position >= hiddenStartRow && position < hiddenEndRow) {
            id = hiddenPills.get(position - hiddenStartRow);
        }
        if (id == -1 || !RateInstances.isRateInstance(id) || getParentActivity() == null) {
            return false;
        }
        final int pillId = id;
        new AlertDialog.Builder(getParentActivity(), resourcesProvider)
                .setTitle(getString(R.string.PillStackRemoveRate))
                .setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    RateInstances.remove(pillId);
                    activePills.remove(Integer.valueOf(pillId));
                    hiddenPills.remove(Integer.valueOf(pillId));
                    saveLayout();
                    updateRowsAndNotify();
                    PillStackEvents.notifyLayoutChanged();
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
        return true;
    }

    private void updateRowsAndNotify() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void openLocationPicker() {
        LocationActivity fragment = new LocationActivity(LocationActivity.LOCATION_TYPE_SEND);
        fragment.setDelegate((location, live, notify, scheduleDate, payStars) -> {
            if (location != null && location.geo != null) {
                String address = location instanceof TLRPC.TL_messageMediaVenue
                        ? ((TLRPC.TL_messageMediaVenue) location).address
                        : null;
                PillStackConfig.setCustomWeatherLocation(location.geo.lat, location.geo._long, address);
                PillStackEvents.notifySettingsChanged(PillType.WEATHER.id);
                AndroidUtilities.runOnUIThread(this::updateRowsAndNotify);
            }
        });
        presentFragment(fragment);
    }

    private String getWeatherLocationValue() {
        if (PillStackConfig.useCurrentLocation()) {
            return getString(R.string.PillStackWeatherCurrentLocation);
        }
        return getWeatherCustomLocationValue();
    }

    private String getWeatherCustomLocationValue() {
        String address = PillStackConfig.weatherAddress.String();
        if (address != null && !address.isEmpty()) {
            return address;
        }
        if (PillStackConfig.hasCustomWeatherLocation()) {
            return String.format(Locale.US, "%.4f, %.4f",
                    PillStackConfig.customWeatherLatitude(), PillStackConfig.customWeatherLongitude());
        }
        return getString(R.string.PillStackWeatherLocationNotSet);
    }

    /** Перетаскивание внутри секции активных пилюль. */
    private class ReorderCallback extends ItemTouchHelper.Callback {

        @Override
        public boolean isLongPressDragEnabled() {
            return true;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            int position = viewHolder.getAdapterPosition();
            if (activeStartRow == -1 || position < activeStartRow || position >= activeEndRow) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder source, @NonNull RecyclerView.ViewHolder target) {
            int from = source.getAdapterPosition();
            int to = target.getAdapterPosition();
            if (activeStartRow == -1 || to < activeStartRow || to >= activeEndRow) {
                return false;
            }
            Collections.swap(activePills, from - activeStartRow, to - activeStartRow);
            listAdapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
            saveLayout();
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            super.onSelectedChanged(viewHolder, actionState);
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                listView.cancelClickRunnables(false);
                viewHolder.itemView.setPressed(true);
            }
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public int getItemViewType(int position) {
            if (position == infiniteScrollingRow) {
                return TYPE_CHECK;
            }
            if (position == activeHeaderRow || position == hiddenHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == hiddenInfoRow) {
                return TYPE_INFO_PRIVACY;
            }
            if (position == scrollingDividerRow || position == activeDividerRow
                    || position == weatherDividerRow || position == resetDividerRow) {
                return TYPE_SHADOW;
            }
            if (position == activeEmptyRow || position == hiddenEmptyRow || position == resetRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_TEXT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == activeEmptyRow || position == hiddenEmptyRow) {
                return false;
            }
            return super.isEnabled(holder);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == infiniteScrollingRow) {
                        cell.setTextAndCheck(getString(R.string.PillStackInfiniteScrolling),
                                PillStackConfig.infiniteScrolling.Bool(), false);
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == activeHeaderRow) {
                        cell.setText(getString(R.string.PillStackActivePills));
                    } else if (position == hiddenHeaderRow) {
                        cell.setText(getString(R.string.PillStackHiddenPills));
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(getString(R.string.PillStackPillsSettingsInfo));
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == activeEmptyRow || position == hiddenEmptyRow) {
                        cell.setText(getString(R.string.PillStackListEmpty), false);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                    } else if (position == resetRow) {
                        cell.setText(getString(R.string.PillStackReset), false);
                        cell.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
                    }
                    break;
                }
                case TYPE_TEXT: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (weatherRow != -1 && position == weatherRow) {
                        cell.setTextAndValueAndIcon(getString(R.string.PillStackWeather),
                                getWeatherLocationValue(), R.drawable.weather_cloudy, false);
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlackText);
                        break;
                    }
                    if (addRateRow != -1 && position == addRateRow) {
                        cell.setTextAndIcon(getString(R.string.PillStackAddRate), R.drawable.msg_add, false);
                        cell.setColors(Theme.key_windowBackgroundWhiteBlueIcon, Theme.key_windowBackgroundWhiteBlueText);
                        break;
                    }
                    int id = -1;
                    boolean divider = false;
                    if (activeStartRow != -1 && position >= activeStartRow && position < activeEndRow) {
                        id = activePills.get(position - activeStartRow);
                        divider = position != activeEndRow - 1;
                    } else if (hiddenStartRow != -1 && position >= hiddenStartRow && position < hiddenEndRow) {
                        id = hiddenPills.get(position - hiddenStartRow);
                        divider = position != hiddenEndRow - 1;
                    }
                    PillRegistry.PillInfo info = id == -1 ? null : PillRegistry.getPillInfo(id);
                    if (info != null) {
                        cell.setTextAndValueAndColorfulIcon(info.name.toString(), "", false,
                                info.iconRes, info.iconColorTop, info.iconColorBottom, divider);
                        cell.setImageLeft(21);
                        cell.setOffsetFromImage(65);
                    }
                    break;
                }
            }
        }
    }
}
