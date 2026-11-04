@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package streaming.mobile

import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.useContents
import platform.AVKit.AVPlayerViewController
import platform.AVKit.AVPlayerViewControllerDelegateProtocol
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.Foundation.NSSelectorFromString
import platform.UIKit.*
import platform.darwin.NSObject
import kotlin.math.abs

/** Only a downward pull on the picture closes inline playback; native controls keep their touches. */
internal class PlayerDismissGesture(
    private val controller: AVPlayerViewController,
    private val onDismiss: () -> Unit,
) : NSObject(), UIGestureRecognizerDelegateProtocol, AVPlayerViewControllerDelegateProtocol {
    private var fullScreen = false
    val recognizer = UIPanGestureRecognizer(this, NSSelectorFromString("handlePan:")).apply {
        delegate = this@PlayerDismissGesture
        maximumNumberOfTouches = 1u
        cancelsTouchesInView = false
    }

    override fun gestureRecognizerShouldBegin(gestureRecognizer: UIGestureRecognizer): Boolean {
        if (fullScreen) return false
        val pan = gestureRecognizer as? UIPanGestureRecognizer ?: return false
        val translation = pan.translationInView(controller.view)
        val downward = translation.useContents { y > 0 && y > abs(x) * 1.3 }
        val width = controller.view.bounds.useContents { size.width }
        val height = controller.view.bounds.useContents { size.height }
        // Exclude the corners and transport strip even when AVKit controls are transparent.
        return downward && pan.locationInView(controller.view).useContents {
            val startX = x - translation.useContents { x }
            val startY = y - translation.useContents { y }
            startX > width * .18 && startX < width * .82 && startY > height * .2 && startY < height * .78
        }
    }

    // AVKit places an interactive view across the whole picture. Cooperate with its gestures:
    // a tap keeps its native action, while a deliberate vertical pull can dismiss the route.
    override fun gestureRecognizer(gestureRecognizer: UIGestureRecognizer,
        shouldRecognizeSimultaneouslyWithGestureRecognizer: UIGestureRecognizer): Boolean = true

    @ObjCSignatureOverride
    override fun playerViewController(playerViewController: AVPlayerViewController,
        willBeginFullScreenPresentationWithAnimationCoordinator: UIViewControllerTransitionCoordinatorProtocol) {
        fullScreen = true
        reset()
    }

    @ObjCSignatureOverride
    override fun playerViewController(playerViewController: AVPlayerViewController,
        willEndFullScreenPresentationWithAnimationCoordinator: UIViewControllerTransitionCoordinatorProtocol) {
        fullScreen = false
    }

    private fun reset() {
        controller.view.transform = CGAffineTransformMakeTranslation(0.0, 0.0)
        controller.view.alpha = 1.0
    }

    @ObjCAction
    fun handlePan(pan: UIPanGestureRecognizer) {
        val distance = pan.translationInView(null).useContents { y.coerceAtLeast(0.0) }
        when (pan.state) {
            UIGestureRecognizerStateChanged -> {
                controller.view.transform = CGAffineTransformMakeTranslation(0.0, distance * .6)
                controller.view.alpha = (1.0 - distance / 500).coerceAtLeast(.65)
            }
            UIGestureRecognizerStateEnded -> {
                val threshold = controller.view.bounds.useContents { (size.height * .3).coerceIn(56.0, 96.0) }
                reset()
                if (shouldDismissVideo(distance.toFloat(), threshold.toFloat())) onDismiss()
            }
            UIGestureRecognizerStateCancelled, UIGestureRecognizerStateFailed -> reset()
        }
    }
}
