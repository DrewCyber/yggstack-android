// Example: Simple peer monitor using the callback API
package main

import (
	"crypto/ed25519"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/yggdrasil-network/yggdrasil-go/src/core"
)

func main() {
	// Generate a key pair
	_, sk, err := ed25519.GenerateKey(nil)
	if err != nil {
		log.Fatal(err)
	}

	// Create logger
	logger := log.New(os.Stdout, "", log.LstdFlags)

	// Create core instance with a listener
	c, err := core.New(
		sk[:ed25519.PrivateKeySize],
		logger,
		core.ListenAddress("tls://[::]:0"),
	)
	if err != nil {
		log.Fatal(err)
	}
	defer c.Stop()

	fmt.Println("Yggdrasil node started")
	fmt.Printf("Address: %s\n", c.Address())
	fmt.Printf("Subnet: %s\n", c.Subnet())

	// Set up peer change callback
	c.SetPeerChangeCallback(func(connected, total int) {
		timestamp := time.Now().Format("15:04:05")

		if connected == 0 && total > 0 {
			fmt.Printf("[%s] ⚠️  WARNING: No peers connected (0/%d)\n", timestamp, total)
		} else if connected > 0 {
			fmt.Printf("[%s] ✓ Peers connected: %d/%d\n", timestamp, connected, total)
		} else {
			fmt.Printf("[%s] ℹ️  No peers configured\n", timestamp)
		}

		// You could trigger actions here:
		// - Update UI
		// - Send notifications
		// - Adjust routing
		// - Log metrics
	})

	fmt.Println("\nMonitoring peer connections...")
	fmt.Println("Add peers using admin API to see callback in action")
	fmt.Println("Press Ctrl+C to exit\n")

	// Wait for interrupt
	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	fmt.Println("\nShutting down...")
}
