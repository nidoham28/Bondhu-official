package com.nidoham.bondhu.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

/**
 * অ্যাপের প্রাথমিক ফন্ট ফ্যামিলি।
 *
 * [FontFamily.SansSerif] ব্যবহার করা হয়েছে (iOS-ে San Francisco, Android-ে Roboto)
 * নেটিভ লুক অ্যান্ড ফিল এবং কম্পাইলেশন এড়ানোর জন্য।
 */
val AppFontFamily: FontFamily = FontFamily.SansSerif

/**
 * টেক্সটকে তার লাইন হাইটের মধ্যে ভার্টিক্যালি সেন্টার করে।
 * গ্লাসমরফিজম বাটন এবং চিপসের জন্য গুরুত্বপূর্ণ।
 */
private val CenteredLineHeightStyle: LineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None
)

/**
 * Material 3 টাইপোগ্রাফি স্কেল।
 *
 * ব্যবহারের নির্দেশিকা:
 * - Display: হিরো সেকশন, বড় নম্বর, ওয়েলকাম স্ক্রিন
 * - Headline: পেজ টাইটেল, সেকশন হেডার, ডায়ালগ টাইটেল
 * - Title: কার্ড হেডার, লিস্ট আইটেম টাইটেল, অ্যাপ বার
 * - Body: প্যারাগ্রাফ, ডেসক্রিপশন, মেসেজ
 * - Label: বাটন, চিপ, ক্যাপশন, ট্যাব, টেক্সট ফিল্ড
 */
val AppTypography: Typography = Typography(
    // Display - সবচেয়ে বড়, হিরো কন্টেন্ট
    displayLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    displayMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    displaySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // Headline - সেকশন হেডার
    headlineLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    headlineMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    headlineSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // Title - কার্ড এবং লিস্ট আইটেম
    titleLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    titleMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    titleSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // Body - পড়ার জন্য কন্টেন্ট
    bodyLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    bodyMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    bodySmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),

    // Label - ইন্টারঅ্যাক্টিভ এলিমেন্ট
    labelLarge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    labelMedium = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    ),
    labelSmall = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )
)

/**
 * কাস্টম টাইপোগ্রাফি টোকেন।
 *
 * Material 3 স্ট্যান্ডার্ডের বাইরে বিশেষ ব্যবহারের ক্ষেত্রে।
 */
object CustomTypography {

    /**
     * ড্যাশবোর্ড স্ট্যাটিস্টিক্সের জন্য (যেমন: "85%")।
     * টাইট লেটার স্পেসিং এবং হেভি ওয়েট।
     */
    val heroStat = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 64.sp,
        lineHeight = 64.sp,
        letterSpacing = (-2).sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * সেকেন্ডারি স্ট্যাটস (যেমন: চার্ট ভ্যালু)।
     */
    val mediumStat = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-1).sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * মনোস্পেস ফন্ট - অর্ডার আইডি, ট্রানজাকশন হ্যাশ।
     * '0' এবং 'O' আলাদা করা যায়।
     */
    val monoCode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * সেকশন ডিভাইডার বা ব্যাকগ্রাউন্ড লেবেলের জন্য।
     * (যেমন: "TODAY", "RECENT")
     */
    val overline = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * ব্যাজ বা নোটিফিকেশন কাউন্টের জন্য।
     * ছোট গোলাকার এলিমেন্টে ব্যবহার।
     */
    val badge = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.2.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * বাটন টেক্সটের জন্য অপশনাল হেভি ভ্যারিয়েন্ট।
     * প্রাইমারি CTA বাটনে ব্যবহার।
     */
    val buttonHeavy = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.5.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * ট্যাগ বা চিপের জন্য মিডিয়াম সাইজ।
     */
    val tag = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )

    /**
     * লিস্টের সাবটাইটেল বা মেটা ডেটার জন্য।
     * লাইট ওয়েটে কম গুরুত্বপূর্ণ তথ্য।
     */
    val meta = TextStyle(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.3.sp,
        lineHeightStyle = CenteredLineHeightStyle
    )
}