/*
 * #%L
 * Wheelmap - App
 * %%
 * Copyright (C) 2011 - 2012 Michal Harakal - Michael Kroez - Sozialhelden e.V.
 * %%
 * Wheelmap App based on the Wheelmap Service by Sozialhelden e.V.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *         http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS-IS" BASIS
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package org.wheelmap.android.manager;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.wheelmap.android.online.R;
import org.wheelmap.android.app.WheelmapApp;
import org.wheelmap.android.model.Extra;
import org.wheelmap.android.model.Extra.What;
import org.wheelmap.android.model.PrefKey;
import org.wheelmap.android.model.Support.CategoriesContent;
import org.wheelmap.android.model.Support.LastUpdateContent;
import org.wheelmap.android.model.Support.LocalesContent;
import org.wheelmap.android.model.Support.NodeTypesContent;
import org.wheelmap.android.service.SyncService;
import org.wheelmap.android.utils.DetachableResultReceiver;
import org.wheelmap.android.utils.GeocoordinatesMath;

import wheelmap.org.WheelchairState;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.preference.PreferenceManager;

import com.squareup.otto.Bus;

import de.akquinet.android.androlog.Log;

public class SupportManager {
	private static final String TAG = SupportManager.class.getSimpleName();
	private final float fMarkerDimension;

	private Context mContext;
	private Map<Integer, NodeType> mNodeTypeLookup;
	private Map<Integer, Category> mCategoryLookup;
	private Map<Integer, String> mCategoryIdentifierLookup = new HashMap<Integer, String>();

	private DetachableResultReceiver mStatusSender;
	private Bus mBus;

	private NodeType mDefaultNodeType;
	private Category mDefaultCategory;
	private boolean mNeedsReloading;

	private final static long MILLISECS_PER_DAY = 1000 * 60 * 60 * 24;
	private final static long DATE_INTERVAL_FOR_UPDATE_IN_DAYS = 1;
	public final static String PREFS_SERVICE_LOCALE = "prefsServiceLocale";
	public final static String PREFS_KEY_UNIT_PREFERENCE = "prefsUnit";

	private boolean mUseAngloDistanceUnit;

	public final static int UNKNOWN_TYPE = 0;

	public final static Map<WheelchairState, WheelchairAttributes> wsAttributes = new HashMap<WheelchairState, WheelchairAttributes>();

	public static class WheelchairAttributes {
		public final int titleStringId;
		public final int stringId;
		public final int settingsStringId;
		public final int drawableId;
		public final int colorId;
		public final String prefsKey;

		WheelchairAttributes(int titleStringId, int stringId,
				int settingsStringId, int drawableId, int colorId,
				String prefsKey) {
			this.titleStringId = titleStringId;
			this.stringId = stringId;
			this.settingsStringId = settingsStringId;
			this.drawableId = drawableId;
			this.colorId = colorId;
			this.prefsKey = prefsKey;
		}
	}

	private AssetManager mAssetManager;

	public static class NodeType {
		public NodeType(int id, String identifier, String localizedName,
				int categoryId) {
			this.id = id;
			this.identifier = identifier;
			this.localizedName = localizedName;
			this.categoryId = categoryId;
		}

		public Drawable iconDrawable;
		public Map<WheelchairState, Drawable> stateDrawables;
		public int id;
		public String identifier;
		public String localizedName;
		public int categoryId;
	}

	public static class NodeTypeComparator implements Comparator<NodeType> {
		@Override
		public int compare(NodeType n1, NodeType n2) {
			return n1.localizedName.compareTo(n2.localizedName);
		}
	}

	public static class Category {
		public Category(int id, String identifier, String localizedName) {
			this.id = id;
			this.identifier = identifier;
			this.localizedName = localizedName;
		}

		public int id;
		public String identifier;
		public String localizedName;
	}

	public static class CategoryComparator implements Comparator<Category> {
		@Override
		public int compare(Category c1, Category c2) {
			return c1.localizedName.compareTo(c2.localizedName);
		}
	}

	public class DistanceUnitChangedEvent {

	}

	public SupportManager(Context ctx) {
		mContext = ctx;

		mBus = WheelmapApp.getBus();
		mBus.register(this);

		fMarkerDimension = mContext.getResources().getDimension(R.dimen.mapmarker_halflength);

		mCategoryLookup = new HashMap<Integer, Category>();
		mNodeTypeLookup = new HashMap<Integer, NodeType>();
		mAssetManager = mContext.getAssets();

		mDefaultCategory = new Category(UNKNOWN_TYPE, "unknown",
				mContext.getString(R.string.support_category_unknown));
		mDefaultNodeType = new NodeType(UNKNOWN_TYPE, "unknown",
				mContext.getString(R.string.support_nodetype_unknown), 0);
		mDefaultNodeType.stateDrawables = createDefaultDrawables();

		mNeedsReloading = false;
		if (!(checkForLocales() && checkForCategories() && checkForNodeTypes())) {
			mNeedsReloading = true;
		}
		Log.i( TAG, "Loading lookup data" );
		initLookup();

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		prefs.registerOnSharedPreferenceChangeListener(prefsListener);
		mUseAngloDistanceUnit = prefs.getBoolean(PREFS_KEY_UNIT_PREFERENCE,
				false);
		GeocoordinatesMath.useAngloDistanceUnit(mUseAngloDistanceUnit);
	}

	public void releaseReceiver() {
		if (mStatusSender != null)
			mStatusSender.clearReceiver();
	}

	public boolean needsReloading() {
		return mNeedsReloading;
	}

	public boolean useAngloDistanceUnit() {
		return mUseAngloDistanceUnit;
	}

	private void initLookup() {
		initCategories();
		initNodeTypes();
	}

	public void reload(DetachableResultReceiver receiver) {
		mStatusSender = receiver;
		Intent localesIntent = new Intent(Intent.ACTION_SYNC, null, mContext,
				SyncService.class);
		localesIntent.putExtra(Extra.WHAT, What.RETRIEVE_LOCALES);
		localesIntent.putExtra(Extra.STATUS_RECEIVER, mStatusSender);
		mContext.startService(localesIntent);
	}

	public void reloadStageTwo() {
		initLocales();
		retrieveCategories();
	}

	public void reloadStageThree() {
		initCategories();
		retrieveNodeTypes();
	}

	public void reloadStageFour() {
		initNodeTypes();
		mNeedsReloading = false;
	}

	public void retrieveCategories() {

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		String locale = prefs.getString(PREFS_SERVICE_LOCALE, "");

		Intent categoriesIntent = new Intent(Intent.ACTION_SYNC, null,
				mContext, SyncService.class);
		categoriesIntent.putExtra(Extra.WHAT, What.RETRIEVE_CATEGORIES);
		categoriesIntent.putExtra(Extra.LOCALE, locale);
		categoriesIntent.putExtra(Extra.STATUS_RECEIVER, mStatusSender);
		mContext.startService(categoriesIntent);
	}

	public void retrieveNodeTypes() {
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		String locale = prefs.getString(PREFS_SERVICE_LOCALE, "");

		Intent nodeTypesIntent = new Intent(Intent.ACTION_SYNC, null, mContext,
				SyncService.class);
		nodeTypesIntent.putExtra(Extra.WHAT, What.RETRIEVE_NODETYPES);
		nodeTypesIntent.putExtra(Extra.LOCALE, locale);

		nodeTypesIntent.putExtra(Extra.STATUS_RECEIVER, mStatusSender);
		mContext.startService(nodeTypesIntent);

	}


	private boolean checkIfUpdateDurationPassed(int module) {
		Date date = LastUpdateContent.queryTimeStamp(mContext.getContentResolver(), module);
		if (date == null)
			return true;

		long now = System.currentTimeMillis();

		long days = (now - date.getTime()) / MILLISECS_PER_DAY;
		Log.d(TAG, "checkIfUpdateDurationPassed: days = " + days);

		if (days >= DATE_INTERVAL_FOR_UPDATE_IN_DAYS) {
			Log.d( TAG, "checkIfUpdateDurationPassed: interval passed - forcing update module = " + module);
			return true;
		}

		return false;

	}

	private boolean checkForLocales() {
		ContentResolver resolver = mContext.getContentResolver();
		Cursor cursor = resolver.query(LocalesContent.CONTENT_URI,
				LocalesContent.PROJECTION, null, null, null);
		if (cursor == null)
			return false;

		boolean dbEmpty = cursor.getCount() == 0;
		cursor.close();

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(mContext);
		String prefsLocale = prefs.getString(PREFS_SERVICE_LOCALE, "");
		boolean prefsEmpty = prefsLocale.equals("");
		boolean updateDurationPassed = checkIfUpdateDurationPassed(LastUpdateContent.MODULE_LOCALE);

		String locale = mContext.getResources().getConfiguration().locale
				.getLanguage();

		if (dbEmpty || prefsEmpty || !locale.equals(prefsLocale) || updateDurationPassed) {
			Log.d(TAG, "dbEmpty = " + dbEmpty + " prefsLocale = " + prefsLocale
					+ " locale = " + locale + " updateDurationPassed = " + updateDurationPassed);
			return false;
		} else
			return true;
	}

	private boolean checkForCategories() {
		ContentResolver resolver = mContext.getContentResolver();
		Cursor cursor = resolver.query(CategoriesContent.CONTENT_URI,
				CategoriesContent.PROJECTION, null, null, null);
		if (cursor == null)
			return false;

		boolean dbFull = cursor.getCount() != 0;
		cursor.close();

		boolean updateDurationPassed = checkIfUpdateDurationPassed(LastUpdateContent.MODULE_CATEGORIES);

		if ( dbFull && !updateDurationPassed)
			return true;
		else
			return false;
	}

	private boolean checkForNodeTypes() {
		ContentResolver resolver = mContext.getContentResolver();
		Cursor cursor = resolver.query(NodeTypesContent.CONTENT_URI,
				NodeTypesContent.PROJECTION, null, null, null);
		if (cursor == null)
			return false;

		boolean dbFull = cursor.getCount() != 0;
		cursor.close();
		boolean updateDurationPassed = checkIfUpdateDurationPassed(LastUpdateContent.MODULE_CATEGORIES);

		if ( dbFull && !updateDurationPassed)
			return true;
		else
			return false;
	}

	public void initLocales() {
		Log.d(TAG, "SupportManager:initLocales");
		String locale = mContext.getResources().getConfiguration().locale
				.getLanguage();
		Log.d(TAG, "SupportManager: locale = " + locale);
		ContentResolver resolver = mContext.getContentResolver();
		String whereClause = "( " + LocalesContent.LOCALE_ID + " = ? )";
		String[] whereValues = { locale };

		Cursor cursor = resolver.query(LocalesContent.CONTENT_URI,
				LocalesContent.PROJECTION, whereClause, whereValues, null);
		if (cursor == null)
			return;

		String serviceLocale = null;
		if (cursor.getCount() == 1)
			serviceLocale = locale;
		else
			serviceLocale = "en";
		cursor.close();

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(mContext);

		String storedLocale = prefs.getString(PREFS_SERVICE_LOCALE, "");
		if (storedLocale.equals("") || !storedLocale.equals(serviceLocale)) {
			prefs.edit().putString(PREFS_SERVICE_LOCALE, serviceLocale)
					.commit();
		}
		Log.i(TAG, "SupportManager:initLocales: serviceLocale = " + serviceLocale);
	}

	public void initCategories() {
		Log.d(TAG, "SupportManager:initCategories");
		ContentResolver resolver = mContext.getContentResolver();
		Cursor cursor = resolver.query(CategoriesContent.CONTENT_URI,
				CategoriesContent.PROJECTION, null, null, null);
		if (cursor == null)
			return;

		cursor.moveToFirst();
		mCategoryLookup.clear();
		mCategoryIdentifierLookup.clear();

		while (!cursor.isAfterLast()) {
			int id = CategoriesContent.getCategoryId(cursor);
			String identifier = CategoriesContent.getIdentifier(cursor);
			String localizedName = CategoriesContent.getLocalizedName(cursor);
			mCategoryLookup.put(id, new Category(id, identifier,
					localizedName));
			mCategoryIdentifierLookup.put(id, identifier);

			cursor.moveToNext();
		}

		cursor.close();
		Log.i( TAG, "Categories count = " + mCategoryLookup.size());
	}

	public void initNodeTypes() {
		// Log.d(TAG, "SupportManager:initNodeTypes");
		ContentResolver resolver = mContext.getContentResolver();
		Cursor cursor = resolver.query(NodeTypesContent.CONTENT_URI,
				NodeTypesContent.PROJECTION, null, null, null);
		if (cursor == null)
			return;

		cursor.moveToFirst();
		mNodeTypeLookup.clear();

		while (!cursor.isAfterLast()) {
			int id = NodeTypesContent.getNodeTypeId(cursor);
			String identifier = NodeTypesContent.getIdentifier(cursor);
			// Log.d(TAG, "Loading nodetype: identifier = " + identifier);
			String localizedName = CategoriesContent.getLocalizedName(cursor);
			int categoryId = NodeTypesContent.getCategoryId(cursor);
			String iconPath = NodeTypesContent.getIconURL(cursor);

			NodeType nodeType = new NodeType(id, identifier, localizedName,
					categoryId);
			nodeType.iconDrawable = createIconDrawable(iconPath);
			nodeType.stateDrawables = createDrawableLookup(iconPath);
			mNodeTypeLookup.put(id, nodeType);
			cursor.moveToNext();
		}

		cursor.close();
		Log.i( TAG, "NodeTypes count = " + mNodeTypeLookup.size());
	}

	private Drawable createIconDrawable(String assetPath) {
		Bitmap bitmap;
		// Log.d(TAG, "SupportManager:createIconDrawable loading " + assetPath);
		try {
			InputStream is = mAssetManager.open("icons/" + assetPath);
			bitmap = BitmapFactory.decodeStream(is);
			is.close();
		} catch (IOException e) {
			Log.w(TAG, "Warning in createIconDrawable : " + e.getMessage());
			return null;
		}
		return new BitmapDrawable(mContext.getResources(), bitmap);

	}

	private Map<WheelchairState, Drawable> createDefaultDrawables() {
		Map<WheelchairState, Drawable> lookupMap = new HashMap<WheelchairState, Drawable>();

		int idx;
		for (idx = 0; idx < WheelchairState.values().length - 1; idx++) {
			String path = String.format("marker/%s.png", WheelchairState
					.valueOf(idx).toString().toLowerCase());
			Drawable drawable = null;

			try {
				InputStream is = mAssetManager.open(path);
				drawable = Drawable.createFromStream(is, null);
				is.close();
			} catch (IOException e) {
				Log.w(TAG, "Error in createDefaultDrawables. " + e.getMessage());
			}

			if (drawable != null)
				drawable.setBounds(- (int) fMarkerDimension, (int)(-fMarkerDimension * 2), (int) fMarkerDimension, 0);
			lookupMap.put(WheelchairState.valueOf(idx), drawable);
		}

		return lookupMap;
	}

	private Map<WheelchairState, Drawable> createDrawableLookup(String assetPath) {
		Map<WheelchairState, Drawable> lookupMap = new HashMap<WheelchairState, Drawable>();
		// Log.d(TAG, "SupportManager:createDrawableLookup loading " +
		// assetPath);

		int idx;
		for (idx = 0; idx < WheelchairState.values().length - 1; idx++) {
			String path = String.format("marker/%s/%s", WheelchairState
					.valueOf(idx).toString().toLowerCase(), assetPath);
			Drawable drawable = null;
			try {
				InputStream is = mAssetManager.open(path);
				drawable = Drawable.createFromStream(is, null);
				is.close();
			} catch (IOException e) {
				Log.w(TAG,
						"Error in createDrawableLookup. Assigning fallback. " + e.getMessage());
				drawable = mDefaultNodeType.stateDrawables.get(WheelchairState
						.valueOf(idx));
			}
			if (drawable != null)
				drawable.setBounds(- (int) fMarkerDimension, (int)(-fMarkerDimension * 2), (int) fMarkerDimension, 0);
			lookupMap.put(WheelchairState.valueOf(idx), drawable);
		}

		return lookupMap;
	}

	public Drawable getDefaultOverlayDrawable() {
		return mDefaultNodeType.stateDrawables.get( WheelchairState.UNKNOWN );
	}

	public void cleanReferences() {
		// Log.d(TAG, "clearing callbacks for mDefaultNodeType ");
		cleanReferences(mDefaultNodeType.stateDrawables);

		for (Integer nodeTypeId : mNodeTypeLookup.keySet()) {
			NodeType nodeType = mNodeTypeLookup.get(nodeTypeId);
			// Log.d(TAG, "clearing callbacks for " + nodeType.identifier);
			cleanReferences(nodeType.stateDrawables);
		}
	}

	public void cleanReferences(Map<WheelchairState, Drawable> lookupMap) {
		for (WheelchairState state : lookupMap.keySet()) {
			Drawable drawable = lookupMap.get(state);
			drawable.setCallback(null);
		}
	}

	public Category lookupCategory(int id) {
		if (mCategoryLookup.containsKey(id))
			return mCategoryLookup.get(id);
		else
			return mDefaultCategory;
	}

	public NodeType lookupNodeType(int id) {
		if (mNodeTypeLookup.containsKey(id))
			return mNodeTypeLookup.get(id);
		else
			return mDefaultNodeType;
	}

	public List<Category> getCategoryList() {
		Set<Integer> keys = mCategoryLookup.keySet();
		List<Category> list = new ArrayList<Category>();
		for (Integer key : keys) {
			list.add(mCategoryLookup.get(key));
		}
		return list;
	}

	public List<NodeType> getNodeTypeListByCategory(int categoryId) {
		Set<Integer> keys = mNodeTypeLookup.keySet();
		List<NodeType> list = new ArrayList<NodeType>();
		for (Integer key : keys) {
			NodeType nodeType = mNodeTypeLookup.get(key);

			if (nodeType.categoryId == categoryId) {
				list.add(nodeType);
			}
		}
		return list;
	}

	private void insertContentValues(Uri contentUri, String[] projection,
			String whereClause, String[] whereValues, ContentValues values) {
		ContentResolver resolver = mContext.getContentResolver();
		Cursor c = resolver.query(contentUri, projection, whereClause,
				whereValues, null);
		if (c == null)
			return;

		int cursorCount = c.getCount();
		c.close();
		if (cursorCount == 0)
			resolver.insert(contentUri, values);
		else if (cursorCount == 1)
			resolver.update(contentUri, values, whereClause, whereValues);
		else {
			// do nothing, as more than one file would be updated
		}
	}

	private OnSharedPreferenceChangeListener prefsListener = new OnSharedPreferenceChangeListener() {

		@Override
		public void onSharedPreferenceChanged(SharedPreferences prefs,
				String key) {
			Log.d(TAG, "onPreferencesChanged: key = " + key);

			if (key.equals(PREFS_KEY_UNIT_PREFERENCE)) {
				mUseAngloDistanceUnit = prefs.getBoolean(
						PREFS_KEY_UNIT_PREFERENCE, false);
				GeocoordinatesMath.useAngloDistanceUnit(mUseAngloDistanceUnit);

				mBus.post(new DistanceUnitChangedEvent());
			}
		}
	};

	static {
		wsAttributes.put(WheelchairState.YES, new WheelchairAttributes(
				R.string.ws_enabled_title, R.string.ws_enabled,
				R.string.settings_wheelchair_yes,
				R.drawable.wheelie_yes, R.color.wheel_enabled,
				PrefKey.WHEELCHAIR_STATE_YES));
		wsAttributes.put(WheelchairState.LIMITED, new WheelchairAttributes(
				R.string.ws_limited_title, R.string.ws_limited,
				R.string.settings_wheelchair_limited,
				R.drawable.wheelie_limited, R.color.wheel_limited,
				PrefKey.WHEELCHAIR_STATE_LIMITED));
		wsAttributes.put(WheelchairState.NO, new WheelchairAttributes(
				R.string.ws_disabled_title, R.string.ws_disabled,
				R.string.settings_wheelchair_no,
				R.drawable.wheelie_no, R.color.wheel_disabled,
				PrefKey.WHEELCHAIR_STATE_NO));
		wsAttributes.put(WheelchairState.UNKNOWN, new WheelchairAttributes(
				R.string.ws_unknown_title, R.string.ws_unknown,
				R.string.settings_wheelchair_unknown,
				R.drawable.wheelie_unknown, R.color.wheel_unknown,
				PrefKey.WHEELCHAIR_STATE_UNKNOWN));

	}

}
