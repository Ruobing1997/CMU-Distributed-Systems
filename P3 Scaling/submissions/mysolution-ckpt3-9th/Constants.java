/**
 * A Constants class for the project.
 * This class provides Configurations and Constants.
 * Configurations are used to configure the server for auto-scaling.
 * Constants are used to define the constants in the project.
 */
public class Constants {
    public static final int MASTER_ID = 1;
    public static final int FRONT_TIER = 0;
    public static final int MID_TIER = 1;
    public static final double CONFIG_FRONT_SCALE_OUT = 4.0;
    public static final double CONFIG_MID_SCALE_OUT = 1.5;
    public static final int MIN_FRONT_TIER_SIZE = 1;
    public static final int MIN_MID_TIER_SIZE = 1;
    public static final int CONFIG_COOL_DOWN_FRONT = 5000;
    public static final float MID_TIER_NO_JOB_TIME = 2500;
    public static final float FRONT_TIER_NO_JOB_TIME = 1500;
    public static final int FIRST_EXTRA_VM_ID = 2;
    public static final int CONFIG_MASTER_DEAL_REQUEST_COUNT_UP_LIMIT = 15;
    public static final int CONFIG_ARRIVAL_RATE_SCALE_OUT = 200;
    public static final int CONFIG_MASTER_BOOT_FRONT_END = 1;
    public static final int CONFIG_MASTER_BOOT_MID_END = 5;
    public static final int CONFIG_DROP_TIME_WHEN_BOOT = 200;
    public static final int CONFIG_DROP_FAST_REQUEST_UP_LIMIT = 30;
    public static final float CONFIG_FAST_REQUEST_RATE = 500;
}