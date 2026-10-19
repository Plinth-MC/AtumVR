package me.phoenixra.atumvr.core.input;

import lombok.Getter;
import me.phoenixra.atumconfig.api.tuples.PairRecord;
import me.phoenixra.atumvr.api.enums.ControllerType;
import me.phoenixra.atumvr.api.exceptions.AtumVRException;
import me.phoenixra.atumvr.api.input.AtumVRInputHandler;
import me.phoenixra.atumvr.api.input.device.AtumVRDevice;
import me.phoenixra.atumvr.core.XRProvider;
import me.phoenixra.atumvr.api.input.action.data.VRActionData;
import me.phoenixra.atumvr.api.input.profile.VRInteractionProfileType;
import me.phoenixra.atumvr.core.input.action.XRAction;
import me.phoenixra.atumvr.core.input.action.XRActionSet;
import me.phoenixra.atumvr.core.input.action.types.HapticPulseAction;
import me.phoenixra.atumvr.api.input.body.AtumVRBodyView;
import me.phoenixra.atumvr.api.input.body.hand.AtumVRHandsView;
import me.phoenixra.atumvr.api.input.haptics.AtumVRBodyHaptics;
import me.phoenixra.atumvr.api.input.treadmill.AtumVRTreadmillView;
import me.phoenixra.atumvr.core.input.body.XRBody;
import me.phoenixra.atumvr.core.input.device.XRDevice;
import me.phoenixra.atumvr.core.input.haptics.XRBodyHapticsProvider;
import me.phoenixra.atumvr.api.input.device.AtumVRDeviceController;
import me.phoenixra.atumvr.core.input.device.XRDeviceController;
import me.phoenixra.atumvr.core.input.hand.XRHandTracking;
import me.phoenixra.atumvr.core.input.profile.XRInteractionProfile;
import me.phoenixra.atumvr.core.input.profile.tracker.XRTrackerProvider;
import me.phoenixra.atumvr.core.input.profile.tracker.hand.XRHandsProvider;
import me.phoenixra.atumvr.core.input.treadmill.XRTreadmillProvider;
import me.phoenixra.atumvr.core.input.profile.types.*;
import me.phoenixra.atumvr.core.session.XRInstance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.openxr.*;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static me.phoenixra.atumvr.api.input.profile.VRInteractionProfileType.*;
import static me.phoenixra.atumvr.api.input.profile.VRInteractionProfileType.VIVE_COSMOS;
import static me.phoenixra.atumvr.core.input.action.XRAction.LEFT_HAND_PATH;
import static me.phoenixra.atumvr.core.input.action.XRAction.RIGHT_HAND_PATH;
import static org.lwjgl.openxr.XR10.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memUTF8;

/**
 * Abstract base class for XR input
 */
public abstract class XRInputHandler implements AtumVRInputHandler {

    @Getter
    private final XRProvider vrProvider;


    private final HashMap<String, Long> paths = new HashMap<>();

    private final Map<String, XRActionSet> actionSets = new LinkedHashMap<>();
    private final Map<String, XRDevice> devices = new LinkedHashMap<>();

    private final List<XRTrackerProvider> trackerProviders = new ArrayList<>();
    private final List<XRTrackerProvider> trackerProvidersView =
            Collections.unmodifiableList(trackerProviders);

    private @Nullable XRHandsProvider handsProvider;
    private @Nullable XRTreadmillProvider treadmillProvider;
    private @Nullable XRBodyHapticsProvider bodyHapticsProvider;

    private final XRBody vrBody;

    private boolean bodyTrackingEnabled = true;
    private boolean handTrackingEnabled = true;
    private boolean treadmillEnabled = true;
    private boolean bodyHapticsEnabled = true;

    private boolean initialized;

    private final List<XRActionSet> appActionSets = new ArrayList<>();
    // action sets synced this frame, rebuilt on init and on feature toggles
    private final List<XRActionSet> activeActionSets = new ArrayList<>();
    // generated body views in priority order, incl. tracker providers
    private final List<AtumVRBodyView> bodyViewSources = new ArrayList<>();

    // detection of these is deferred until the feature is first enabled
    private List<? extends XRTreadmillProvider> treadmillCandidates = List.of();
    private List<? extends XRBodyHapticsProvider> bodyHapticsCandidates = List.of();
    private boolean treadmillResolved;
    private boolean bodyHapticsResolved;

    private final Map<String, String> lastLoggedInteractionProfile = new HashMap<>();
    private final Map<ControllerType, String> currentProfilePaths = new EnumMap<>(ControllerType.class);

    /** Tracked hands, used in place of a controller that is not active */
    @Getter
    private XRHandTracking handTracking;
    private final boolean[] handDriven = new boolean[ControllerType.values().length];


    public XRInputHandler(@NotNull XRProvider vrProvider){
        this.vrProvider = vrProvider;
        this.vrBody = new XRBody();
    }

    // -------- SETTING UP --------

    /**
     * Generate action sets
     *
     * @param stack the memory stack to use
     * @return the list containing action sets
     */
    protected abstract @NotNull List<? extends XRActionSet> generateActionSets(@NotNull MemoryStack stack);

    /**
     * Generate VR devices
     *
     * @param stack the memory stack to use
     * @return the list containing VR devices
     */
    protected abstract @NotNull List<? extends XRDevice> generateDevices(@NotNull MemoryStack stack);


    protected @NotNull List<? extends AtumVRBodyView> generateBodyViews(@NotNull MemoryStack stack){
        return List.of();
    }

    /**
     * Generate hands provider candidates in priority order,
     * the first supported one becomes the hands source
     *
     * @param stack the memory stack to use
     * @return the list containing hands provider candidates
     */
    protected @NotNull List<? extends XRHandsProvider> generateHandsProviders(@NotNull MemoryStack stack){
        return List.of();
    }

    /**
     * Generate treadmill provider candidates in priority order,
     * the first supported one becomes the treadmill
     *
     * @param stack the memory stack to use
     * @return the list containing treadmill provider candidates
     */
    protected @NotNull List<? extends XRTreadmillProvider> generateTreadmillProviders(@NotNull MemoryStack stack){
        return List.of();
    }

    /**
     * Generate body haptics provider candidates in priority order,
     * the first supported one becomes the body haptics vendor
     *
     * @param stack the memory stack to use
     * @return the list containing body haptics provider candidates
     */
    protected @NotNull List<? extends XRBodyHapticsProvider> generateBodyHapticsProviders(@NotNull MemoryStack stack){
        return List.of();
    }


    // -------- LIFECYCLE --------

    @Override
    public void init() {

        XrSession xrSession = vrProvider.getSession().getHandle();


        try (MemoryStack stack = MemoryStack.stackPush()) {


            //LOAD ACTION SETS
            actionSets.clear();
            appActionSets.clear();
            appActionSets.addAll(generateActionSets(stack));
            List<XRActionSet> loadedActionSets = new ArrayList<>(appActionSets);

            //LOAD BODY VIEWS
            //action sets of disabled providers are still created and attached,
            //attaching is once per session and runtime enabling needs them
            trackerProviders.clear();
            bodyViewSources.clear();
            handsProvider = null;
            treadmillProvider = null;
            bodyHapticsProvider = null;
            vrBody.clearSources();

            for(AtumVRBodyView bodyView : generateBodyViews(stack)){
                if(bodyView instanceof XRTrackerProvider provider){
                    if(!provider.isSupported()){
                        logUnsupportedProvider(provider);
                        continue;
                    }
                    trackerProviders.add(provider);
                    loadedActionSets.addAll(provider.getActionSets());
                }
                bodyViewSources.add(bodyView);
            }

            //LOAD HANDS PROVIDER (single slot, first supported candidate wins)
            for(XRHandsProvider provider : generateHandsProviders(stack)){
                if(!provider.isSupported()){
                    logUnsupportedProvider(provider);
                    continue;
                }
                if(handsProvider != null){
                    logProviderSlotTaken(provider, handsProvider);
                    continue;
                }
                handsProvider = provider;
            }

            //LOAD TREADMILL / BODY HAPTICS CANDIDATES
            //selection is deferred while the feature is disabled,
            //so their hardware detection costs nothing until enabled
            treadmillResolved = false;
            treadmillCandidates = List.copyOf(generateTreadmillProviders(stack));
            if(treadmillEnabled){
                resolveTreadmillProvider();
            }

            bodyHapticsResolved = false;
            bodyHapticsCandidates = List.copyOf(generateBodyHapticsProviders(stack));
            if(bodyHapticsEnabled){
                resolveBodyHapticsProvider();
            }

            loadedActionSets.forEach(XRActionSet::init);

            long[] actionSetsArray = new long[loadedActionSets.size()];
            int i = 0;
            for(XRActionSet entry : loadedActionSets){
                actionSets.put(entry.getName(), entry);
                actionSetsArray[i] = entry.getHandle().address();
                i++;
            }

            //suggest defaults before attaching action sets
            suggestDefaultBindings();

            //attach action sets
            XrSessionActionSetsAttachInfo attach_info = XrSessionActionSetsAttachInfo.calloc(stack).set(
                    XR10.XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO,
                    NULL,
                    stackPointers(actionSetsArray)
            );
            vrProvider.checkXRError(
                    XR10.xrAttachSessionActionSets(xrSession, attach_info),
                    "xrAttachSessionActionSets"
            );


            if(bodyTrackingEnabled){
                trackerProviders.forEach(XRTrackerProvider::onAttached);
            }
            if(handTrackingEnabled && handsProvider != null){
                handsProvider.onAttached();
            }
            //treadmill/haptics providers only get resolved while enabled
            if(treadmillProvider != null){
                treadmillProvider.onAttached();
            }
            if(bodyHapticsProvider != null){
                bodyHapticsProvider.onAttached();
            }
            //HAND TRACKING (before devices, controllers read it)
            handTracking = new XRHandTracking(vrProvider);
            handTracking.init(xrSession, vrProvider.getSession().getSystem().getSystemId());

            //LOAD DEVICES
            devices.clear();
            for(XRDevice entry : generateDevices(stack)){
                devices.put(entry.getId(), entry);
            }
            if(bodyTrackingEnabled){
                registerTrackerDevices();
            }

            rebuildActiveActionSets();
            rebuildBodySources();
        }
        initialized = true;
    }

    private void resolveTreadmillProvider() {
        if(treadmillResolved) return;
        treadmillResolved = true;
        for(XRTreadmillProvider provider : treadmillCandidates){
            if(!provider.isSupported()){
                logUnsupportedProvider(provider);
                continue;
            }
            if(treadmillProvider != null){
                logProviderSlotTaken(provider, treadmillProvider);
                continue;
            }
            treadmillProvider = provider;
        }
        treadmillCandidates = List.of();
    }

    private void resolveBodyHapticsProvider() {
        if(bodyHapticsResolved) return;
        bodyHapticsResolved = true;
        for(XRBodyHapticsProvider provider : bodyHapticsCandidates){
            if(!provider.isSupported()){
                logUnsupportedProvider(provider);
                continue;
            }
            if(bodyHapticsProvider != null){
                logProviderSlotTaken(provider, bodyHapticsProvider);
                continue;
            }
            bodyHapticsProvider = provider;
        }
        bodyHapticsCandidates = List.of();
    }

    private void registerTrackerDevices() {
        for(XRTrackerProvider provider : trackerProviders){
            for(XRDevice entry : provider.getDevices()){
                devices.put(entry.getId(), entry);
            }
        }
    }

    private void rebuildActiveActionSets() {
        activeActionSets.clear();
        activeActionSets.addAll(appActionSets);
        if(bodyTrackingEnabled){
            for(XRTrackerProvider provider : trackerProviders){
                activeActionSets.addAll(provider.getActionSets());
            }
        }
    }

    private void rebuildBodySources() {
        vrBody.clearSources();
        if(bodyTrackingEnabled){
            bodyViewSources.forEach(vrBody::addSource);
        }
        // e.g. hand skeletons also deliver wrist/palm body joints
        if(handTrackingEnabled && handsProvider instanceof AtumVRBodyView bodyView){
            vrBody.addSource(bodyView);
        }
    }

    private void logUnsupportedProvider(@NotNull Object provider) {
        vrProvider.getLogger().logInfo(
                provider.getClass().getSimpleName()
                        + " is unsupported by the user's hardware - skipping"
        );
    }

    private void logProviderSlotTaken(@NotNull Object provider, @NotNull Object active) {
        vrProvider.getLogger().logInfo(
                provider.getClass().getSimpleName()
                        + " skipped - slot already taken by "
                        + active.getClass().getSimpleName()
        );
    }


    @Override
    public void update() {
        XrInstance instance = vrProvider.getSession().getInstance().getHandle();
        XrSession session = vrProvider.getSession().getHandle();

        // Sync actions, disabled features are excluded so
        // the runtime skips the work for their action sets
        if (!activeActionSets.isEmpty()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {

                XrActiveActionSet.Buffer toUpdate = XrActiveActionSet
                        .calloc(activeActionSets.size(), stack);
                int i = 0;
                for (XRActionSet actionSet : activeActionSets) {
                    toUpdate.get(i).set(actionSet.getHandle(), XR_NULL_PATH);
                    i++;
                }

                XrActionsSyncInfo syncInfo = XrActionsSyncInfo
                        .calloc(stack)
                        .type(XR_TYPE_ACTIONS_SYNC_INFO)
                        .activeActionSets(toUpdate);
                vrProvider.checkXRError(
                        xrSyncActions(session, syncInfo),
                        "xrSyncActions"
                );


            }
        }

        // hands first: the Touch profile and controller devices fall back to them
        if (handTracking != null) {
            handTracking.update();
        }
        for (ControllerType type : ControllerType.values()) {
            // last frame's device state (devices update after the actions)
            handDriven[type.ordinal()] = devices.get(AtumVRDeviceController.getId(type))
                    instanceof XRDeviceController controller && controller.isHandDriven();
        }
        for (XRActionSet entry : activeActionSets) {
            if (entry instanceof OculusTouchXRProfile touch) {
                touch.applyHands(handTracking, handDriven);
            }
            entry.update();
        }
        if (bodyTrackingEnabled) {
            for (XRTrackerProvider entry : trackerProviders) {
                entry.update();
            }
        }
        if (handTrackingEnabled && handsProvider != null) {
            handsProvider.update();
        }
        if (treadmillEnabled && treadmillProvider != null) {
            treadmillProvider.update();
        }
        if (bodyHapticsEnabled && bodyHapticsProvider != null) {
            bodyHapticsProvider.update();
        }
        for (XRDevice entry : devices.values()) {
            entry.update();
        }
        vrBody.update();

    }

    /**
     * On action data changed
     * <p>
     *     Override if you need a simple way to listen for actions data change<br>
     *     (e.g. button pressed/released, pose changed etc.)
     * </p>
     *
     * @param actionData the action data that was changed
     */
    public void onActionChanged(@NotNull VRActionData actionData){
        //your implementation
    }

    private void suggestDefaultBindings() {

        XrInstance xrInstance = vrProvider.getSession().getInstance().getHandle();
        List<VRInteractionProfileType> supportedProfiles = getSupportedProfileTypes();

        for (VRInteractionProfileType profileType : supportedProfiles) {
            List<PairRecord<XRAction, String>> bindingsSet = new ArrayList<>();
            for(XRActionSet actionSet : actionSets.values()){
                var binds = actionSet.getDefaultBindings(profileType);
                if(binds == null || binds.isEmpty()) continue;
                bindingsSet.addAll(binds);
            }
            if(bindingsSet.isEmpty()) continue;

            suggestBindings(xrInstance, profileType.getXrPath(), bindingsSet);
        }
    }

    private void suggestBindings(@NotNull XrInstance xrInstance,
                                 @NotNull String profilePath,
                                 @NotNull List<PairRecord<XRAction, String>> bindingsSet) {
        // Suggest whole bindings set
        int result = trySuggestBindings(xrInstance, profilePath, bindingsSet);
        if (result == XR10.XR_SUCCESS) {
            return;
        }
        if (result != XR10.XR_ERROR_PATH_UNSUPPORTED) {
            vrProvider.checkXRError(result, "xrSuggestInteractionProfileBindings", profilePath);
            return;
        }

        //Fallback
        List<PairRecord<XRAction, String>> supported = new ArrayList<>();
        for (PairRecord<XRAction, String> binding : bindingsSet) {
            if (trySuggestBindings(xrInstance, profilePath, List.of(binding)) == XR10.XR_SUCCESS) {
                supported.add(binding);
            } else {
                vrProvider.getLogger().logWarn(
                        "Dropping unsupported binding for " + profilePath + ": " + binding.second()
                );
            }
        }

        if (supported.isEmpty()) {
            vrProvider.getLogger().logWarn(
                    "No supported bindings for interaction profile " + profilePath + " - skipping"
            );
            return;
        }

        // Re-suggest the supported subset
        vrProvider.checkXRError(
                trySuggestBindings(xrInstance, profilePath, supported),
                "xrSuggestInteractionProfileBindings", profilePath
        );
    }

    /**
     * Query the runtime for the interaction profile currently bound to each hand.
     * <p>
     *     Called on XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED and after init
     * </p>
     */
    public void refreshCurrentInteractionProfiles() {
        currentProfilePaths.put(ControllerType.LEFT, queryCurrentInteractionProfile(LEFT_HAND_PATH));
        currentProfilePaths.put(ControllerType.RIGHT, queryCurrentInteractionProfile(RIGHT_HAND_PATH));
        vrProvider.getLogger().logInfo(
                "Current interaction profile: left=" + currentProfilePaths.get(ControllerType.LEFT)
                        + ", right=" + currentProfilePaths.get(ControllerType.RIGHT)
        );
    }

    /**
     * Get the interaction profile path the runtime currently uses for the hand
     *
     * @param controllerType the hand
     * @return the profile path (e.g. /interaction_profiles/oculus/touch_controller)
     *         or null if none / not known yet
     */
    public @Nullable String getCurrentInteractionProfilePath(@NotNull ControllerType controllerType) {
        return currentProfilePaths.get(controllerType);
    }

    private @Nullable String queryCurrentInteractionProfile(@NotNull String handPath) {
        XrSession session = vrProvider.getSession().getHandle();
        XrInstance instance = vrProvider.getSession().getInstance().getHandle();
        try (MemoryStack stack = stackPush()) {
            XrInteractionProfileState state = XrInteractionProfileState.calloc(stack)
                    .type(XR_TYPE_INTERACTION_PROFILE_STATE);
            int result = xrGetCurrentInteractionProfile(session, convertStringToXrPath(handPath), state);
            if (result < 0) {
                vrProvider.checkXRError(false, result, "xrGetCurrentInteractionProfile", handPath);
                return null;
            }
            long profilePath = state.interactionProfile();
            if (profilePath == XR_NULL_PATH) {
                return null;
            }
            IntBuffer length = stack.callocInt(1);
            ByteBuffer buffer = stack.malloc(XR_MAX_PATH_LENGTH);
            result = xrPathToString(instance, profilePath, length, buffer);
            if (result < 0 || length.get(0) <= 0) {
                vrProvider.checkXRError(false, result, "xrPathToString", handPath);
                return null;
            }
            // length includes the null terminator
            return memUTF8(buffer, length.get(0) - 1);
        }
    }


    private int trySuggestBindings(@NotNull XrInstance xrInstance,
                                   @NotNull String profilePath,
                                   @NotNull List<PairRecord<XRAction, String>> bindingsSet) {
        try (MemoryStack stack = stackPush()) {
            var bindings = XrActionSuggestedBinding.calloc(bindingsSet.size(), stack);
            for (int i = 0; i < bindingsSet.size(); i++) {
                var binding = bindingsSet.get(i);
                bindings.get(i).set(
                        binding.first().getHandle(),
                        convertStringToXrPath(binding.second())
                );
            }

            var suggested_binds = XrInteractionProfileSuggestedBinding.calloc(stack)
                    .set(
                            XR10.XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING,
                            NULL,
                            convertStringToXrPath(profilePath),
                            bindings
                    );

            return XR10.xrSuggestInteractionProfileBindings(xrInstance, suggested_binds);
        }
    }

    // -------- API --------
    @Override
    public Collection<? extends XRActionSet> getActionSets(){
        return actionSets.values();
    }

    @Override
    public Collection<? extends XRDevice> getDevices() {
        return devices.values();
    }

    @Override
    public boolean isBodyTrackingEnabled() {
        return bodyTrackingEnabled;
    }

    @Override
    public void setBodyTrackingEnabled(boolean enabled) {
        if (bodyTrackingEnabled == enabled) {
            return;
        }
        bodyTrackingEnabled = enabled;
        if (!initialized) {
            return;
        }
        if (enabled) {
            trackerProviders.forEach(XRTrackerProvider::onAttached);
            registerTrackerDevices();
        } else {
            for (XRTrackerProvider provider : trackerProviders) {
                for (XRDevice entry : provider.getDevices()) {
                    devices.remove(entry.getId(), entry);
                }
                destroySafely(provider, provider::destroy);
            }
        }
        rebuildActiveActionSets();
        rebuildBodySources();
        logFeatureToggled("Body tracking", enabled);
    }

    @Override
    public boolean isHandTrackingEnabled() {
        return handTrackingEnabled;
    }

    @Override
    public void setHandTrackingEnabled(boolean enabled) {
        if (handTrackingEnabled == enabled) {
            return;
        }
        handTrackingEnabled = enabled;
        if (!initialized || handsProvider == null) {
            return;
        }
        if (enabled) {
            handsProvider.onAttached();
        } else {
            destroySafely(handsProvider, handsProvider::destroy);
        }
        rebuildBodySources();
        logFeatureToggled("Hand tracking", enabled);
    }

    @Override
    public boolean isTreadmillEnabled() {
        return treadmillEnabled;
    }

    @Override
    public void setTreadmillEnabled(boolean enabled) {
        if (treadmillEnabled == enabled) {
            return;
        }
        treadmillEnabled = enabled;
        if (!initialized) {
            return;
        }
        if (enabled) {
            resolveTreadmillProvider();
            if (treadmillProvider != null) {
                treadmillProvider.onAttached();
                logFeatureToggled("Treadmill", true);
            }
        } else if (treadmillProvider != null) {
            destroySafely(treadmillProvider, treadmillProvider::destroy);
            logFeatureToggled("Treadmill", false);
        }
    }

    @Override
    public boolean isBodyHapticsEnabled() {
        return bodyHapticsEnabled;
    }

    @Override
    public void setBodyHapticsEnabled(boolean enabled) {
        if (bodyHapticsEnabled == enabled) {
            return;
        }
        bodyHapticsEnabled = enabled;
        if (!initialized) {
            return;
        }
        if (enabled) {
            resolveBodyHapticsProvider();
            if (bodyHapticsProvider != null) {
                bodyHapticsProvider.onAttached();
                logFeatureToggled("Body haptics", true);
            }
        } else if (bodyHapticsProvider != null) {
            destroySafely(bodyHapticsProvider, bodyHapticsProvider::destroy);
            logFeatureToggled("Body haptics", false);
        }
    }

    private void logFeatureToggled(@NotNull String feature, boolean enabled) {
        vrProvider.getLogger().logInfo(
                feature + (enabled ? " enabled" : " disabled") + " at runtime"
        );
    }

    @Override
    public @NotNull XRBody getVRBody() {
        return vrBody;
    }

    @Override
    public @NotNull AtumVRHandsView getVRHands() {
        return handTrackingEnabled && handsProvider != null
                ? handsProvider : AtumVRHandsView.EMPTY;
    }

    @Override
    public @NotNull AtumVRTreadmillView getVRTreadmill() {
        return treadmillEnabled && treadmillProvider != null
                ? treadmillProvider : AtumVRTreadmillView.EMPTY;
    }

    @Override
    public @NotNull AtumVRBodyHaptics getVRBodyHaptics() {
        return bodyHapticsEnabled && bodyHapticsProvider != null
                ? bodyHapticsProvider : AtumVRBodyHaptics.EMPTY;
    }

    /**
     * Get the tracker providers that survived {@link XRTrackerProvider#isSupported()}
     *
     * @return the immutable view of active providers
     */
    public @NotNull List<XRTrackerProvider> getTrackerProviders() {
        return trackerProvidersView;
    }

    /**
     * Get an active tracker provider by its type
     *
     * @param type the provider class
     * @param <T>  the provider type
     * @return the provider, or null if it is not active
     */
    public <T extends XRTrackerProvider> @Nullable T getTrackerProvider(@NotNull Class<T> type) {
        for (XRTrackerProvider provider : trackerProviders) {
            if (type.isInstance(provider)) {
                return type.cast(provider);
            }
        }
        return null;
    }

    /**
     * Get the active hands provider
     *
     * @return the provider, or null if none is supported
     */
    public @Nullable XRHandsProvider getHandsProvider() {
        return handsProvider;
    }

    /**
     * Get the active treadmill provider
     *
     * @return the provider, or null if none is supported
     *         or the feature has never been enabled
     */
    public @Nullable XRTreadmillProvider getTreadmillProvider() {
        return treadmillProvider;
    }

    /**
     * Get the active body haptics provider
     *
     * @return the provider, or null if none is supported
     *         or the feature has never been enabled
     */
    public @Nullable XRBodyHapticsProvider getBodyHapticsProvider() {
        return bodyHapticsProvider;
    }

    @Override
    public XRDevice getDevice(String id) {
        return devices.get(id);
    }

    @Override
    public  <T extends AtumVRDevice> T getDevice(String id, Class<T> clazz){
        return (T) getDevice(id);
    }

    @Override
    public void registerDevice(@NotNull AtumVRDevice device) {
        if(!(device instanceof XRDevice xrDevice)){
            throw new AtumVRException("Tried to register VRDevice that is not an instance of XRDevice! Id: "+device.getId());
        }
        devices.put(device.getId(), xrDevice);
    }


    /**
     * Converts an OpenXR path string to a path handle
     *
     * @param pathString the path string (e.g., "/user/hand/left")
     * @return the OpenXR path handle
     * @throws AtumVRException if the path format is invalid
     */
    public long convertStringToXrPath(@NotNull String pathString) {
        return paths.computeIfAbsent(pathString, s -> {
            try (MemoryStack ignored = stackPush()) {
                LongBuffer buf = stackCallocLong(1);
                int xrResult = XR10.xrStringToPath(
                        vrProvider.getSession().getInstance().getHandle(),
                        pathString, buf
                );
                if (xrResult == XR10.XR_ERROR_PATH_FORMAT_INVALID) {
                    throw new AtumVRException("Invalid path:\"" + pathString + "\"");
                } else {
                    vrProvider.checkXRError(xrResult, "xrStringToPath");
                }
                return buf.get();
            }
        });
    }

    /**
     * Converts an OpenXR path handle back to its path string
     *
     * @param path the OpenXR path handle
     * @return the path string, or null if the handle could not be resolved
     */
    public @Nullable String convertXrPathToString(long path) {
        XrInstance xrInstance = vrProvider.getSession().getInstance().getHandle();
        try (MemoryStack stack = stackPush()) {
            var sizeBuf = stack.callocInt(1);

            int xrResult = XR10.xrPathToString(xrInstance, path, sizeBuf, null);
            if (xrResult < 0) {
                vrProvider.checkXRError(false, xrResult, "xrPathToString", "size");
                return null;
            }

            int size = sizeBuf.get(0);
            var valueBuf = stack.calloc(size);
            xrResult = XR10.xrPathToString(xrInstance, path, sizeBuf, valueBuf);
            if (xrResult < 0) {
                vrProvider.checkXRError(false, xrResult, "xrPathToString", "value");
                return null;
            }

            return memUTF8(valueBuf, size - 1);
        }
    }


    /**
     * Get the interaction profile the runtime currently has bound to a top level user path.
     *
     * <p>
     *     Requires action sets to be attached, returns null before that
     * </p>
     *
     * @param userPath top level user path, e.g. {@link XRAction#LEFT_HAND_PATH}
     * @return the interaction profile path, or null if nothing is bound
     */
    public @Nullable String getCurrentInteractionProfilePath(@NotNull String userPath){
        try (MemoryStack stack = stackPush()) {
            var state = XrInteractionProfileState.calloc(stack)
                    .type(XR10.XR_TYPE_INTERACTION_PROFILE_STATE);

            int result = XR10.xrGetCurrentInteractionProfile(
                    vrProvider.getSession().getHandle(),
                    convertStringToXrPath(userPath),
                    state
            );
            if (result < 0) {
                vrProvider.checkXRError(false, result, "xrGetCurrentInteractionProfile", userPath);
                return null;
            }

            long profilePath = state.interactionProfile();
            return profilePath == XR10.XR_NULL_PATH
                    ? null
                    : convertXrPathToString(profilePath);
        }
    }

    /**
     * Log the interaction profile bound to each hand.
     *
     * <p>
     *     Reports profiles the runtime picked that this app suggested no bindings for -
     *     those leave every action inactive, which reads as dead controllers
     * </p>
     */
    public void logCurrentInteractionProfiles(){
        for (String userPath : List.of(XRAction.LEFT_HAND_PATH, XRAction.RIGHT_HAND_PATH)) {
            String profilePath = getCurrentInteractionProfilePath(userPath);
            String key = profilePath == null ? "" : profilePath;

            // The runtime can fire this event repeatedly, only log actual changes
            if (key.equals(lastLoggedInteractionProfile.put(userPath, key))) {
                continue;
            }

            if (profilePath == null) {
                vrProvider.getLogger().logInfo(
                        "Interaction profile for " + userPath + ": none (controller off or not bound)"
                );
                continue;
            }

            var type = VRInteractionProfileType.fromXRPath(profilePath);
            if (type == null) {
                vrProvider.getLogger().logWarn(
                        "Interaction profile for " + userPath + ": " + profilePath
                                + " - UNSUPPORTED, no bindings were suggested for it,"
                                + " input from this controller will stay inactive"
                );
                continue;
            }

            vrProvider.getLogger().logInfo(
                    "Interaction profile for " + userPath + ": " + profilePath + " (" + type + ")"
            );
        }
    }

    @Override
    public @NotNull List<VRInteractionProfileType> getSupportedProfileTypes(){
        List<VRInteractionProfileType> list = new ArrayList<>();
        XRInstance instance = vrProvider.getSession().getInstance();

        list.add(OCULUS_TOUCH);
        list.add(VALVE_INDEX);
        list.add(WINDOWS_MOTION);
        list.add(VIVE);

        if(instance.getHandle().getCapabilities().XR_EXT_hp_mixed_reality_controller){
            list.add(HP_MIXED_REALITY);
        }
        if(instance.getHandle().getCapabilities().XR_HTC_vive_cosmos_controller_interaction){
            list.add(VIVE_COSMOS);
        }
        if(instance.getHandle().getCapabilities().XR_HTCX_vive_tracker_interaction){
            list.add(VIVE_TRACKER);
        }
        if(instance.isExtensionEnabled(SteamFrameXRProfile.EXTENSION_NAME)){
            list.add(STEAM_FRAME);
        }

        return list;
    }

    /**
     * Get supported interaction profiles by the user's hardware
     */
    public @NotNull List<XRInteractionProfile> getSupportedProfiles(){
        var out = new ArrayList<XRInteractionProfile>();
        var supported = getSupportedProfileTypes();
        if(supported.contains(VALVE_INDEX)) out.add(new ValveIndexXRProfile(vrProvider));

        if(supported.contains(OCULUS_TOUCH)) out.add(new OculusTouchXRProfile(vrProvider));

        if(supported.contains(WINDOWS_MOTION)) out.add(new WindowsMotionXRProfile(vrProvider));

        if(supported.contains(HP_MIXED_REALITY)) out.add(new HpMixedRealityXRProfile(vrProvider));

        if(supported.contains(VIVE)) out.add(new ViveXRProfile(vrProvider));

        if(supported.contains(VIVE_COSMOS)) out.add(new ViveCosmosXRProfile(vrProvider));

        if(supported.contains(STEAM_FRAME)) out.add(new SteamFrameXRProfile(vrProvider));

        return out;
    }

    // -------- DESTROY --------

    @Override
    public void destroy() {
        initialized = false;
        stopActiveHaptics();

        for (XRTrackerProvider provider : trackerProviders) {
            destroySafely(provider, provider::destroy);
        }
        if (handsProvider != null) {
            destroySafely(handsProvider, handsProvider::destroy);
            handsProvider = null;
        }
        if (treadmillProvider != null) {
            destroySafely(treadmillProvider, treadmillProvider::destroy);
            treadmillProvider = null;
        }
        if (bodyHapticsProvider != null) {
            destroySafely(bodyHapticsProvider, bodyHapticsProvider::destroy);
            bodyHapticsProvider = null;
        }
        trackerProviders.clear();
        bodyViewSources.clear();
        treadmillCandidates = List.of();
        bodyHapticsCandidates = List.of();
        treadmillResolved = false;
        bodyHapticsResolved = false;
        vrBody.clearSources();

        if (handTracking != null) {
            handTracking.destroy();
            handTracking = null;
        }
        actionSets.values().forEach(XRActionSet::destroy);
        actionSets.clear();
        appActionSets.clear();
        activeActionSets.clear();
        devices.clear();
        paths.clear();
        lastLoggedInteractionProfile.clear();
        currentProfilePaths.clear();
    }

    private void destroySafely(@NotNull Object provider, @NotNull Runnable destroy) {
        try {
            destroy.run();
        } catch (Throwable t) {
            vrProvider.getLogger().logError(
                    provider.getClass().getSimpleName() + ".destroy() failed: " + t.getMessage()
            );
        }
    }

    public void stopActiveHaptics() {
        if (vrProvider.getSession().getHandle() == null) {
            return;
        }
        for (XRActionSet actionSet : actionSets.values()) {
            for (XRAction action : actionSet.getActions()) {
                if (action instanceof HapticPulseAction haptic) {
                    try {
                        haptic.stop();
                    } catch (Throwable ignored) {

                    }
                }
            }
        }
    }
}

