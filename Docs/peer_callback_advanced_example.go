// Example: Advanced peer monitoring with metrics and health checks
package main

import (
	"crypto/ed25519"
	"fmt"
	"log"
	"os"
	"os/signal"
	"sync"
	"syscall"
	"time"

	"github.com/yggdrasil-network/yggdrasil-go/src/core"
)

// PeerMonitor tracks connection health metrics
type PeerMonitor struct {
	mu                sync.RWMutex
	connected         int
	total             int
	lastChange        time.Time
	consecutiveAlerts int
	healthHistory     []HealthStatus
	alertThreshold    int
}

type HealthStatus struct {
	Timestamp time.Time
	Connected int
	Total     int
	HealthPct float64
}

func NewPeerMonitor(alertThreshold int) *PeerMonitor {
	return &PeerMonitor{
		alertThreshold: alertThreshold,
		healthHistory:  make([]HealthStatus, 0, 100),
		lastChange:     time.Now(),
	}
}

func (pm *PeerMonitor) UpdatePeers(connected, total int) {
	pm.mu.Lock()
	defer pm.mu.Unlock()

	pm.connected = connected
	pm.total = total
	pm.lastChange = time.Now()

	// Calculate health percentage
	healthPct := 0.0
	if total > 0 {
		healthPct = float64(connected) / float64(total) * 100
	}

	// Record health status
	status := HealthStatus{
		Timestamp: time.Now(),
		Connected: connected,
		Total:     total,
		HealthPct: healthPct,
	}
	pm.healthHistory = append(pm.healthHistory, status)

	// Keep only last 100 entries
	if len(pm.healthHistory) > 100 {
		pm.healthHistory = pm.healthHistory[1:]
	}

	// Check for degraded connectivity
	if connected == 0 && total > 0 {
		pm.consecutiveAlerts++
		if pm.consecutiveAlerts >= pm.alertThreshold {
			pm.triggerCriticalAlert()
		}
	} else {
		pm.consecutiveAlerts = 0
	}

	pm.logStatus(status)
}

func (pm *PeerMonitor) triggerCriticalAlert() {
	fmt.Printf("\n🚨 CRITICAL: No peers connected for %d consecutive checks!\n", pm.consecutiveAlerts)
	fmt.Println("   Recommended actions:")
	fmt.Println("   - Check network connectivity")
	fmt.Println("   - Verify peer configuration")
	fmt.Println("   - Review firewall settings")
	fmt.Println()
}

func (pm *PeerMonitor) logStatus(status HealthStatus) {
	timestamp := status.Timestamp.Format("15:04:05")

	if status.Total == 0 {
		fmt.Printf("[%s] ℹ️  No peers configured\n", timestamp)
		return
	}

	icon := "✓"
	if status.Connected == 0 {
		icon = "⚠️ "
	} else if status.HealthPct < 50 {
		icon = "⚡"
	}

	fmt.Printf("[%s] %s Peers: %d/%d (%.0f%% healthy)\n",
		timestamp, icon, status.Connected, status.Total, status.HealthPct)
}

func (pm *PeerMonitor) GetAverageHealth(duration time.Duration) float64 {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	if len(pm.healthHistory) == 0 {
		return 0.0
	}

	cutoff := time.Now().Add(-duration)
	var sum float64
	var count int

	for _, status := range pm.healthHistory {
		if status.Timestamp.After(cutoff) {
			sum += status.HealthPct
			count++
		}
	}

	if count == 0 {
		return 0.0
	}

	return sum / float64(count)
}

func (pm *PeerMonitor) PrintStats() {
	pm.mu.RLock()
	defer pm.mu.RUnlock()

	fmt.Println("\n=== Peer Connection Statistics ===")
	fmt.Printf("Current: %d/%d peers connected\n", pm.connected, pm.total)
	fmt.Printf("Last change: %s ago\n", time.Since(pm.lastChange).Round(time.Second))

	if len(pm.healthHistory) > 0 {
		avgHealth5m := pm.GetAverageHealth(5 * time.Minute)
		avgHealth1h := pm.GetAverageHealth(1 * time.Hour)

		fmt.Printf("Avg health (5m): %.1f%%\n", avgHealth5m)
		fmt.Printf("Avg health (1h): %.1f%%\n", avgHealth1h)
		fmt.Printf("Total checks: %d\n", len(pm.healthHistory))
	}
	fmt.Println("==================================\n")
}

func main() {
	// Generate a key pair
	_, sk, err := ed25519.GenerateKey(nil)
	if err != nil {
		log.Fatal(err)
	}

	// Create logger
	logger := log.New(os.Stdout, "", log.LstdFlags)

	// Create core instance
	c, err := core.New(
		sk[:ed25519.PrivateKeySize],
		logger,
		core.ListenAddress("tls://[::]:0"),
	)
	if err != nil {
		log.Fatal(err)
	}
	defer c.Stop()

	fmt.Println("=== Advanced Peer Monitor ===")
	fmt.Printf("Address: %s\n", c.Address())
	fmt.Printf("Subnet: %s\n", c.Subnet())
	fmt.Println()

	// Create monitor with alert threshold of 3 consecutive failures
	monitor := NewPeerMonitor(3)

	// Set up peer change callback
	c.SetPeerChangeCallback(func(connected, total int) {
		monitor.UpdatePeers(connected, total)
	})

	// Start periodic stats reporter
	statsTicker := time.NewTicker(30 * time.Second)
	go func() {
		for range statsTicker.C {
			monitor.PrintStats()
		}
	}()
	defer statsTicker.Stop()

	fmt.Println("Monitoring peer connections with health tracking...")
	fmt.Println("Stats will be printed every 30 seconds")
	fmt.Println("Press Ctrl+C to exit\n")

	// Wait for interrupt
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	fmt.Println("\nFinal statistics:")
	monitor.PrintStats()
	fmt.Println("Shutting down...")
}
