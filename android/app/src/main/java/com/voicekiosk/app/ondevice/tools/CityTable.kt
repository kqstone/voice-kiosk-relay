package com.voicekiosk.app.ondevice.tools

/** A named place with coordinates for the weather tool. */
data class City(val name: String, val latitude: Double, val longitude: Double)

/**
 * Built-in coordinates for common Chinese cities so the weather tool works without a
 * geocoding round trip (and the L0 rules can recognise city names safely). Anything
 * else goes through Open-Meteo geocoding at execution time.
 */
object CityTable {
    val CITIES: List<City> = listOf(
        City("北京", 39.9042, 116.4074), City("上海", 31.2304, 121.4737), City("广州", 23.1291, 113.2644),
        City("深圳", 22.5431, 114.0579), City("杭州", 30.2741, 120.1551), City("成都", 30.5728, 104.0668),
        City("武汉", 30.5928, 114.3055), City("南京", 32.0603, 118.7969), City("西安", 34.3416, 108.9398),
        City("重庆", 29.5630, 106.5516), City("苏州", 31.2990, 120.5853), City("天津", 39.0842, 117.2009),
        City("长沙", 28.2282, 112.9388), City("郑州", 34.7466, 113.6254), City("青岛", 36.0671, 120.3826),
        City("济南", 36.6512, 117.1201), City("沈阳", 41.8057, 123.4315), City("大连", 38.9140, 121.6147),
        City("哈尔滨", 45.8038, 126.5349), City("长春", 43.8171, 125.3235), City("福州", 26.0745, 119.2965),
        City("厦门", 24.4798, 118.0894), City("合肥", 31.8206, 117.2272), City("南昌", 28.6820, 115.8579),
        City("昆明", 25.0389, 102.7183), City("贵阳", 26.6470, 106.6302), City("南宁", 22.8170, 108.3665),
        City("海口", 20.0440, 110.1999), City("三亚", 18.2528, 109.5120), City("太原", 37.8706, 112.5489),
        City("石家庄", 38.0428, 114.5149), City("呼和浩特", 40.8424, 111.7492), City("兰州", 36.0611, 103.8343),
        City("西宁", 36.6171, 101.7782), City("银川", 38.4872, 106.2309), City("乌鲁木齐", 43.8256, 87.6168),
        City("拉萨", 29.6500, 91.1000), City("无锡", 31.4912, 120.3119), City("宁波", 29.8683, 121.5440),
        City("温州", 27.9943, 120.6994), City("东莞", 23.0207, 113.7518), City("佛山", 23.0215, 113.1214),
        City("珠海", 22.2707, 113.5767), City("常州", 31.8107, 119.9741), City("南通", 31.9802, 120.8943),
        City("徐州", 34.2058, 117.2852), City("烟台", 37.4638, 121.4479), City("绍兴", 30.0303, 120.5802),
        City("嘉兴", 30.7469, 120.7555), City("金华", 29.0791, 119.6474), City("台州", 28.6564, 121.4208),
        City("泉州", 24.8741, 118.6759), City("洛阳", 34.6197, 112.4540), City("扬州", 32.3936, 119.4129),
        City("桂林", 25.2736, 110.2900), City("香港", 22.3193, 114.1694), City("澳门", 22.1987, 113.5439),
        City("台北", 25.0330, 121.5654), City("保定", 38.8738, 115.4646), City("唐山", 39.6309, 118.1802),
        City("潍坊", 36.7069, 119.1618), City("惠州", 23.1115, 114.4152), City("中山", 22.5170, 113.3926)
    )

    private val byName = CITIES.associateBy { it.name }

    /** Exact built-in match, tolerating a trailing 市. */
    fun find(name: String): City? = byName[name.trim().removeSuffix("市")]

    /** Regex alternation of the built-in names, longest first. */
    val NAME_PATTERN: String = CITIES.map { it.name }.sortedByDescending { it.length }.joinToString("|")
}
