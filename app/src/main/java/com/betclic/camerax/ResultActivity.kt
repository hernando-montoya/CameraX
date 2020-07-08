package com.betclic.camerax

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.redmadrobot.inputmask.MaskedTextChangedListener
import com.redmadrobot.inputmask.helper.AffinityCalculationStrategy
import kotlinx.android.synthetic.main.activity_result.*

class ResultActivity : AppCompatActivity() {

    companion object {
        const val DRAWABLE_RIGHT = 2
        const val IBAN_VALUE = "IBAN_VALUE"
        private const val FR_IBAN_MASK = "FR[00] [0000] [0000] [0000] [0000] [0000] [000]"

        fun newIntent(context: Context, ibanValue: String?) =
            Intent(context, ResultActivity::class.java).apply {
                putExtra("IBAN_VALUE", ibanValue)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        iban_text_value.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                if (event.x >= (iban_text_value.right - iban_text_value.compoundDrawables[DRAWABLE_RIGHT].bounds.width())) {
                    applicationContext.startActivity(
                        CameraActivity.newIntent(
                            applicationContext
                        )
                    )
                    true
                }
            }
            false
        }


        MaskedTextChangedListener.installOn(
            iban_text_value,
            FR_IBAN_MASK,
            listOf(FR_IBAN_MASK), AffinityCalculationStrategy.PREFIX,
            object : MaskedTextChangedListener.ValueListener {
                override fun onTextChanged(
                    maskFilled: Boolean,
                    extractedValue: String,
                    formattedValue: String
                ) {
                    //
                }
            }
        )

        iban_text_value.setText(intent.getStringExtra(IBAN_VALUE))
    }
}