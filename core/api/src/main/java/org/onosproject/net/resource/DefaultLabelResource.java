package org.onosproject.net.resource;

import java.util.Objects;

import org.onosproject.net.Annotations;
import org.onosproject.net.DeviceId;
import org.onosproject.net.provider.ProviderId;
import static com.google.common.base.MoreObjects.toStringHelper;

/**
 * the implementation of a label resource of a device.
 */
public final class DefaultLabelResource implements LabelResource {

    private DeviceId deviceId;

    private LabelResourceId labelResourceId;

    /**
     * Initialize a label resource object.
     * @param deviceId device identifier
     * @param labelResourceId label resource id
     */
    public DefaultLabelResource(String deviceId, long labelResourceId) {
        this.deviceId = DeviceId.deviceId(deviceId);
        this.labelResourceId = LabelResourceId.labelResourceId(labelResourceId);
    }

    /**
     * Initialize a label resource object.
     * @param deviceId device identifier
     * @param labelResourceId label resource id
     */
    public DefaultLabelResource(DeviceId deviceId,
                                LabelResourceId labelResourceId) {
        this.deviceId = deviceId;
        this.labelResourceId = labelResourceId;
    }

    @Override
    public DeviceId deviceId() {
        return deviceId;
    }

    @Override
    public LabelResourceId labelResourceId() {
        return labelResourceId;
    }

    @Override
    public Annotations annotations() {
        return null;
    }

    @Override
    public ProviderId providerId() {
        return null;
    }

    @Override
    public int hashCode() {
        return Objects.hash(deviceId, labelResourceId);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof DefaultLabelResource) {
            DefaultLabelResource that = (DefaultLabelResource) obj;
            return Objects.equals(this.deviceId, that.deviceId)
                    && Objects.equals(this.labelResourceId,
                                      that.labelResourceId);
        }
        return false;
    }

    @Override
    public String toString() {
        return toStringHelper(this).add("deviceId", deviceId)
                .add("labelResourceId", labelResourceId).toString();
    }
}
