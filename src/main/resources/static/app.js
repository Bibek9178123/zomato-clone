/**
 * Zomato Clone - Premium Frontend Application
 * Interacts with Spring Boot Backend & MySQL
 */

// Determine API Base URL:
// If served directly from Render, use current origin; otherwise default to hosted Render URL.
const API_BASE = window.location.origin.includes("onrender.com")
    ? window.location.origin
    : "https://zomato-clone-zabj.onrender.com";

// Application State
const state = {
    user: null,               // { token, userId, email, role, name }
    restaurants: [],          // Raw list from backend
    filteredRestaurants: [],  // Filtered list
    currentRestaurant: null,  // Currently active restaurant in menu view
    menuItems: [],            // Items for current restaurant
    cart: [],                 // [{ id, name, price, isVeg, qty, restaurantId, restaurantName }]
    appliedPromo: null,       // { code: 'WELCOME50', discount: 50, max: 150 }
    stripe: null,
    cardElement: null,
    activeCategory: 'all',
    userCoords: null,
    filters: {
        pureVeg: false,
        minRating: 0,
        fastDelivery: false,
        searchQuery: '',
        sortBy: 'default'
    }
};

// ==========================================================================
// INITIALIZATION
// ==========================================================================

document.addEventListener('DOMContentLoaded', async () => {
    loadUserFromStorage();
    loadCartFromStorage();
    setupEventListeners();
    initStripe();
    await fetchRestaurants();
});

// ==========================================================================
// TOAST NOTIFICATIONS
// ==========================================================================

function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    const toast = document.createElement('div');
    toast.className = `toast ${type}`;

    let icon = 'fa-circle-info';
    if (type === 'success') icon = 'fa-circle-check';
    if (type === 'error') icon = 'fa-circle-exclamation';

    toast.innerHTML = `<i class="fa-solid ${icon}"></i> <span>${message}</span>`;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100%)';
        setTimeout(() => toast.remove(), 300);
    }, 3500);
}

// ==========================================================================
// AUTHENTICATION MANAGEMENT
// ==========================================================================

function authHeaders() {
    return state.user && state.user.token 
        ? { 'Authorization': `Bearer ${state.user.token}`, 'Content-Type': 'application/json' }
        : { 'Content-Type': 'application/json' };
}

function loadUserFromStorage() {
    const savedUser = localStorage.getItem('zomato_user');
    if (savedUser) {
        try {
            state.user = JSON.parse(savedUser);
            updateNavAuthUI();
        } catch (e) {
            localStorage.removeItem('zomato_user');
        }
    }
}

function saveUserToStorage(userData) {
    state.user = userData;
    localStorage.setItem('zomato_user', JSON.stringify(userData));
    updateNavAuthUI();
}

function updateNavAuthUI() {
    const authBtns = document.getElementById('auth-buttons');
    const userMenu = document.getElementById('user-profile-menu');
    const myOrdersNavBtn = document.getElementById('my-orders-nav-btn');

    if (state.user && state.user.token) {
        authBtns.classList.add('hidden');
        userMenu.classList.remove('hidden');
        myOrdersNavBtn.classList.remove('hidden');

        const displayName = state.user.name || state.user.email.split('@')[0];
        document.getElementById('nav-user-name').textContent = displayName;
        document.getElementById('dropdown-user-name').textContent = displayName;
        document.getElementById('dropdown-user-email').textContent = state.user.email;
    } else {
        authBtns.classList.remove('hidden');
        userMenu.classList.add('hidden');
        myOrdersNavBtn.classList.add('hidden');
    }
}

function logout() {
    state.user = null;
    localStorage.removeItem('zomato_user');
    updateNavAuthUI();
    document.getElementById('user-dropdown').classList.remove('active');
    showToast('Logged out successfully', 'info');
}

// ==========================================================================
// RESTAURANTS & MENU DATA
// ==========================================================================

async function fetchRestaurants() {
    const grid = document.getElementById('restaurants-grid');
    grid.innerHTML = '<div class="text-center py-4" style="grid-column: 1/-1;"><i class="fa-solid fa-spinner fa-spin fa-2x"></i><p>Loading fresh restaurants...</p></div>';

    try {
        const response = await fetch(`${API_BASE}/api/restaurants/public/all`);
        if (response.ok) {
            const data = await response.json();
            if (data && data.length > 0) {
                state.restaurants = data;
            } else {
                state.restaurants = getMockRestaurants();
            }
        } else {
            state.restaurants = getMockRestaurants();
        }
    } catch (error) {
        console.warn('Backend unavailable or network error. Using sample restaurants for demonstration.', error);
        state.restaurants = getMockRestaurants();
    }

    applyFiltersAndRender();
}

async function fetchRestaurantMenu(restaurantId) {
    try {
        const res = await fetch(`${API_BASE}/api/restaurants/public/${restaurantId}/menu`);
        if (res.ok) {
            const data = await res.json();
            if (data && data.length > 0) {
                state.menuItems = data;
                renderRestaurantMenu();
                return;
            }
        }
    } catch (err) {
        console.warn('Using fallback menu for restaurant id', restaurantId);
    }
    // Fallback menu
    state.menuItems = getMockMenuItems(restaurantId);
    renderRestaurantMenu();
}

// ==========================================================================
// RENDERING VIEWS
// ==========================================================================

function applyFiltersAndRender() {
    let list = [...state.restaurants];

    // Search query
    const query = state.filters.searchQuery.toLowerCase().trim();
    if (query) {
        list = list.filter(r => 
            (r.name && r.name.toLowerCase().includes(query)) ||
            (r.cuisineType && r.cuisineType.toLowerCase().includes(query)) ||
            (r.description && r.description.toLowerCase().includes(query))
        );
    }

    // Category filter
    if (state.activeCategory && state.activeCategory !== 'all') {
        list = list.filter(r => 
            (r.cuisineType && r.cuisineType.toLowerCase().includes(state.activeCategory.toLowerCase())) ||
            (r.description && r.description.toLowerCase().includes(state.activeCategory.toLowerCase()))
        );
    }

    // Pure veg filter
    if (state.filters.pureVeg) {
        list = list.filter(r => r.cuisineType && (r.cuisineType.toLowerCase().includes('veg') || r.description.toLowerCase().includes('vegetarian')));
    }

    // Rating filter
    if (state.filters.minRating > 0) {
        list = list.filter(r => (r.rating || 0) >= state.filters.minRating);
    }

    // Fast delivery
    if (state.filters.fastDelivery) {
        list = list.filter(r => (r.avgDeliveryTime || 30) <= 30);
    }

    // Sorting
    if (state.filters.sortBy === 'rating') {
        list.sort((a, b) => (b.rating || 0) - (a.rating || 0));
    } else if (state.filters.sortBy === 'delivery') {
        list.sort((a, b) => (a.avgDeliveryTime || 30) - (b.avgDeliveryTime || 30));
    } else if (state.filters.sortBy === 'minOrder') {
        list.sort((a, b) => (a.minOrderAmount || 0) - (b.minOrderAmount || 0));
    }

    state.filteredRestaurants = list;
    renderRestaurantGrid(list);
}

function renderRestaurantGrid(restaurants) {
    const grid = document.getElementById('restaurants-grid');
    const emptyState = document.getElementById('no-restaurants-state');
    const countLabel = document.getElementById('restaurants-count-label');

    countLabel.textContent = `${restaurants.length} restaurants available`;

    if (!restaurants || restaurants.length === 0) {
        grid.innerHTML = '';
        emptyState.classList.remove('hidden');
        return;
    }

    emptyState.classList.add('hidden');
    grid.innerHTML = restaurants.map(r => `
        <div class="restaurant-card" data-id="${r.id}">
            <div class="card-img-wrapper">
                <img src="${r.imageUrl || 'https://images.unsplash.com/photo-1555396273-367ea4eb4db5?w=500&auto=format&fit=crop&q=80'}" alt="${r.name}" loading="lazy">
                <div class="delivery-badge">
                    <i class="fa-regular fa-clock"></i> ${r.avgDeliveryTime || 30} mins
                </div>
                ${!r.open && r.open !== undefined ? '<span class="status-badge">Closed</span>' : ''}
            </div>
            <div class="card-details">
                <div class="card-header-row">
                    <h3>${r.name}</h3>
                    <div class="rating-badge">
                        ${r.rating ? Number(r.rating).toFixed(1) : '4.2'} <i class="fa-solid fa-star" style="font-size:0.65rem;"></i>
                    </div>
                </div>
                <div class="card-cuisine">${r.cuisineType || 'North Indian, Fast Food, Snacks'}</div>
                <div class="card-location">
                    <span><i class="fa-solid fa-location-dot" style="font-size:0.75rem;"></i> ${r.address || 'Central City'}</span>
                    ${r.distanceKm ? `<span class="distance-badge"><i class="fa-solid fa-location-arrow"></i> ${r.distanceKm} km</span>` : ''}
                </div>
                <div class="card-footer-row">
                    <span>Min: <strong>₹${r.minOrderAmount || 99}</strong></span>
                    <span class="text-green"><i class="fa-solid fa-shield-halved"></i> Safety Certified</span>
                </div>
            </div>
        </div>
    `).join('');

    // Attach card click handlers
    grid.querySelectorAll('.restaurant-card').forEach(card => {
        card.addEventListener('click', () => {
            const id = Number(card.dataset.id);
            openRestaurantMenu(id);
        });
    });
}

function openRestaurantMenu(restaurantId) {
    const restaurant = state.restaurants.find(r => r.id === restaurantId);
    if (!restaurant) return;

    state.currentRestaurant = restaurant;

    // Switch view
    document.getElementById('view-restaurants').classList.remove('active');
    document.getElementById('view-restaurant-menu').classList.add('active');
    window.scrollTo({ top: 0, behavior: 'smooth' });

    // Render Restaurant Details Header
    const headerContainer = document.getElementById('restaurant-detail-header');
    headerContainer.innerHTML = `
        <div class="detail-title-row">
            <div>
                <h1>${restaurant.name}</h1>
                <p style="color:var(--text-muted); font-size:0.95rem;">${restaurant.cuisineType || 'Gourmet Cuisine, Fast Food'}</p>
                <p style="color:var(--text-light); font-size:0.85rem;"><i class="fa-solid fa-location-dot"></i> ${restaurant.address || 'Main Street, City'}</p>
            </div>
            <div class="rating-badge" style="font-size:1.05rem; padding:6px 12px;">
                ${restaurant.rating ? Number(restaurant.rating).toFixed(1) : '4.3'} <i class="fa-solid fa-star" style="font-size:0.8rem;"></i>
            </div>
        </div>
        <div class="detail-meta-pill">
            <span><i class="fa-regular fa-clock"></i> <strong>${restaurant.avgDeliveryTime || 30} mins</strong> Delivery</span>
            <span><i class="fa-solid fa-wallet"></i> <strong>₹${restaurant.minOrderAmount || 100}</strong> Min Order</span>
            <span><i class="fa-solid fa-circle-check text-green"></i> <strong>FSSAI Approved</strong></span>
        </div>
    `;

    fetchRestaurantMenu(restaurantId);
}

function renderRestaurantMenu() {
    const tabsContainer = document.getElementById('menu-categories-tabs');
    const itemsGrid = document.getElementById('menu-items-grid');

    // Extract categories
    const categories = ['all', ...new Set(state.menuItems.map(item => item.category || 'Specialties'))];

    tabsContainer.innerHTML = categories.map((cat, idx) => `
        <button class="tab-chip ${idx === 0 ? 'active' : ''}" data-category="${cat}">
            ${cat === 'all' ? 'All Items' : cat}
        </button>
    `).join('');

    tabsContainer.querySelectorAll('.tab-chip').forEach(btn => {
        btn.addEventListener('click', () => {
            tabsContainer.querySelectorAll('.tab-chip').forEach(b => b.classList.remove('active'));
            btn.classList.add('active');
            filterMenuItemsByCategory(btn.dataset.category);
        });
    });

    filterMenuItemsByCategory('all');
}

function filterMenuItemsByCategory(category) {
    const itemsGrid = document.getElementById('menu-items-grid');
    const items = category === 'all' 
        ? state.menuItems 
        : state.menuItems.filter(item => (item.category || 'Specialties') === category);

    if (items.length === 0) {
        itemsGrid.innerHTML = '<p class="text-muted" style="grid-column:1/-1;">No dishes found in this category.</p>';
        return;
    }

    itemsGrid.innerHTML = items.map(item => {
        const cartItem = state.cart.find(c => c.id === item.id);
        const qty = cartItem ? cartItem.qty : 0;
        const isVeg = item.veg !== undefined ? item.veg : (item.isVeg !== undefined ? item.isVeg : true);

        return `
            <div class="menu-card" data-item-id="${item.id}">
                <div class="menu-card-info">
                    <div class="${isVeg ? 'veg-icon' : 'non-veg-icon'}" title="${isVeg ? 'Vegetarian' : 'Non-Vegetarian'}"></div>
                    <h4 class="menu-card-title">${item.name}</h4>
                    <div class="menu-card-price">₹${Number(item.price).toFixed(2)}</div>
                    <p class="menu-card-desc">${item.description || 'Delicious dish made with freshly sourced ingredients and rich spices.'}</p>
                </div>
                <div class="menu-card-action">
                    <div class="menu-card-img-wrap">
                        <img src="${item.imageUrl || 'https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=300&auto=format&fit=crop&q=80'}" alt="${item.name}" loading="lazy">
                    </div>
                    <div class="action-btn-container" id="action-container-${item.id}">
                        ${qty > 0 ? `
                            <div class="qty-stepper">
                                <button class="stepper-btn minus" data-id="${item.id}">-</button>
                                <span>${qty}</span>
                                <button class="stepper-btn plus" data-id="${item.id}">+</button>
                            </div>
                        ` : `
                            <button class="add-btn" data-id="${item.id}">ADD</button>
                        `}
                    </div>
                </div>
            </div>
        `;
    }).join('');

    // Attach Add / Stepper events
    itemsGrid.querySelectorAll('.add-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const id = Number(btn.dataset.id);
            const item = state.menuItems.find(i => i.id === id);
            if (item) addItemToCart(item);
        });
    });

    itemsGrid.querySelectorAll('.stepper-btn').forEach(btn => {
        btn.addEventListener('click', (e) => {
            const id = Number(btn.dataset.id);
            if (btn.classList.contains('plus')) {
                updateCartQuantity(id, 1);
            } else {
                updateCartQuantity(id, -1);
            }
        });
    });
}

// ==========================================================================
// CART MANAGEMENT
// ==========================================================================

function loadCartFromStorage() {
    const savedCart = localStorage.getItem('zomato_cart');
    if (savedCart) {
        try {
            state.cart = JSON.parse(savedCart);
            updateCartBadge();
        } catch (e) {
            state.cart = [];
        }
    }
}

function saveCartToStorage() {
    localStorage.setItem('zomato_cart', JSON.stringify(state.cart));
    updateCartBadge();
}

function updateCartBadge() {
    const badge = document.getElementById('cart-counter');
    const totalQty = state.cart.reduce((sum, item) => sum + item.qty, 0);
    badge.textContent = totalQty;
    document.getElementById('drawer-items-count').textContent = `(${totalQty} items)`;
}

function addItemToCart(menuItem) {
    // Check if adding from another restaurant
    if (state.cart.length > 0 && state.currentRestaurant) {
        const firstItemRestId = state.cart[0].restaurantId;
        if (firstItemRestId && firstItemRestId !== state.currentRestaurant.id) {
            const confirmReplace = confirm(
                `Your cart contains items from "${state.cart[0].restaurantName || 'another restaurant'}". Discard selection and start a new order with "${state.currentRestaurant.name}"?`
            );
            if (confirmReplace) {
                state.cart = [];
            } else {
                return;
            }
        }
    }

    const existing = state.cart.find(c => c.id === menuItem.id);
    if (existing) {
        existing.qty += 1;
    } else {
        state.cart.push({
            id: menuItem.id,
            name: menuItem.name,
            price: Number(menuItem.price),
            isVeg: menuItem.veg !== undefined ? menuItem.veg : (menuItem.isVeg !== undefined ? menuItem.isVeg : true),
            qty: 1,
            restaurantId: state.currentRestaurant ? state.currentRestaurant.id : 1,
            restaurantName: state.currentRestaurant ? state.currentRestaurant.name : 'Restaurant'
        });
    }

    saveCartToStorage();
    renderRestaurantMenu();
    showToast(`Added ${menuItem.name} to cart`, 'success');
}

function updateCartQuantity(itemId, delta) {
    const index = state.cart.findIndex(c => c.id === itemId);
    if (index > -1) {
        state.cart[index].qty += delta;
        if (state.cart[index].qty <= 0) {
            state.cart.splice(index, 1);
        }
    }

    saveCartToStorage();
    renderRestaurantMenu();
    renderCartDrawer();
}

function renderCartDrawer() {
    const emptyState = document.getElementById('empty-cart-state');
    const activeContent = document.getElementById('active-cart-content');
    const itemsList = document.getElementById('cart-items-list');
    const checkoutBtn = document.getElementById('cart-checkout-btn');

    if (state.cart.length === 0) {
        emptyState.classList.remove('hidden');
        activeContent.classList.add('hidden');
        checkoutBtn.disabled = true;
        document.getElementById('cta-total-amount').textContent = '0.00';
        return;
    }

    emptyState.classList.add('hidden');
    activeContent.classList.remove('hidden');
    checkoutBtn.disabled = false;

    // Restaurant Header
    document.getElementById('cart-restaurant-name').textContent = state.cart[0].restaurantName || 'Selected Kitchen';

    // Items list
    itemsList.innerHTML = state.cart.map(item => `
        <div class="cart-item-row">
            <div class="cart-item-left">
                <div class="${item.isVeg ? 'veg-icon' : 'non-veg-icon'}" style="margin-bottom:0;"></div>
                <span class="cart-item-name">${item.name}</span>
            </div>
            <div class="cart-item-stepper">
                <button class="cart-stepper-btn" data-id="${item.id}" data-action="minus">-</button>
                <span>${item.qty}</span>
                <button class="cart-stepper-btn" data-id="${item.id}" data-action="plus">+</button>
            </div>
            <div class="cart-item-price">₹${(item.price * item.qty).toFixed(2)}</div>
        </div>
    `).join('');

    // Stepper event binding in drawer
    itemsList.querySelectorAll('.cart-stepper-btn').forEach(b => {
        b.addEventListener('click', () => {
            const id = Number(b.dataset.id);
            const delta = b.dataset.action === 'plus' ? 1 : -1;
            updateCartQuantity(id, delta);
        });
    });

    // Bill Calculations
    calculateBill();
}

function calculateBill() {
    const subtotal = state.cart.reduce((sum, item) => sum + (item.price * item.qty), 0);
    const platformFee = 5.00;
    const taxes = subtotal * 0.05; // 5% GST

    let discount = 0;
    if (state.appliedPromo) {
        discount = (subtotal * state.appliedPromo.discount) / 100;
        if (state.appliedPromo.max && discount > state.appliedPromo.max) {
            discount = state.appliedPromo.max;
        }
    }

    const grandTotal = Math.max(0, subtotal - discount + platformFee + taxes);

    document.getElementById('bill-subtotal').textContent = subtotal.toFixed(2);
    document.getElementById('bill-tax').textContent = taxes.toFixed(2);
    document.getElementById('bill-grand-total').textContent = grandTotal.toFixed(2);
    document.getElementById('cta-total-amount').textContent = grandTotal.toFixed(2);

    const discountRow = document.getElementById('bill-discount-row');
    if (discount > 0) {
        discountRow.classList.remove('hidden');
        document.getElementById('bill-discount').textContent = discount.toFixed(2);
    } else {
        discountRow.classList.add('hidden');
    }

    return { subtotal, discount, grandTotal };
}

// ==========================================================================
// STRIPE PAYMENT INTEGRATION
// ==========================================================================

async function initStripe() {
    try {
        const res = await fetch(`${API_BASE}/api/payments/config`);
        if (res.ok) {
            const data = await res.json();
            if (data.publishableKey && window.Stripe) {
                state.stripe = Stripe(data.publishableKey);
                const elements = state.stripe.elements();
                state.cardElement = elements.create('card', {
                    style: {
                        base: {
                            fontSize: '15px',
                            color: '#1C1C1C',
                            '::placeholder': { color: '#93959F' }
                        }
                    }
                });
                state.cardElement.mount('#stripe-card-element');
            }
        }
    } catch (e) {
        console.warn('Stripe initialization skipped or offline', e);
    }
}

// ==========================================================================
// ORDER CHECKOUT FLOW
// ==========================================================================

async function handleCheckout() {
    if (!state.user || !state.user.token) {
        openAuthModal('login');
        showToast('Please log in to proceed with your order', 'warning');
        return;
    }

    if (state.cart.length === 0) {
        showToast('Your cart is empty', 'error');
        return;
    }

    const address = document.getElementById('cart-delivery-address').value.trim() 
        || document.getElementById('delivery-location').value.trim();

    if (!address) {
        showToast('Please enter your delivery address', 'error');
        document.getElementById('cart-delivery-address').focus();
        return;
    }

    const notes = document.getElementById('cart-order-notes').value.trim();
    const paymentType = document.querySelector('input[name="payment-type"]:checked').value;
    const checkoutBtn = document.getElementById('cart-checkout-btn');

    checkoutBtn.disabled = true;
    checkoutBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Processing Order...';

    try {
        // 1. Create order on Spring Boot Backend
        const orderPayload = {
            restaurantId: state.cart[0].restaurantId,
            items: state.cart.map(c => ({
                menuItemId: c.id,
                quantity: c.qty
            })),
            deliveryAddress: address,
            notes: notes,
            promoCode: state.appliedPromo ? state.appliedPromo.code : null
        };

        const orderRes = await fetch(`${API_BASE}/api/orders`, {
            method: 'POST',
            headers: authHeaders(),
            body: JSON.stringify(orderPayload)
        });

        if (!orderRes.ok) {
            const errData = await orderRes.json().catch(() => ({}));
            throw new Error(errData.message || 'Failed to place order with restaurant');
        }

        const orderData = await orderRes.json();

        // 2. Handle Payment
        if (paymentType === 'card' && state.stripe && state.cardElement) {
            checkoutBtn.innerHTML = '<i class="fa-solid fa-spinner fa-spin"></i> Confirming Payment...';

            // Request Payment Intent
            const intentRes = await fetch(`${API_BASE}/api/payments/create-intent`, {
                method: 'POST',
                headers: authHeaders(),
                body: JSON.stringify({ orderId: orderData.id })
            });

            if (intentRes.ok) {
                const intentData = await intentRes.json();
                if (intentData.clientSecret) {
                    const result = await state.stripe.confirmCardPayment(intentData.clientSecret, {
                        payment_method: { card: state.cardElement }
                    });

                    if (result.error) {
                        throw new Error(result.error.message);
                    }
                }
            }
        }

        // 3. Success!
        state.cart = [];
        state.appliedPromo = null;
        saveCartToStorage();
        closeCartDrawer();

        // Show Success Modal
        document.getElementById('success-order-num').textContent = orderData.orderNumber || `ZMT-${Date.now()}`;
        document.getElementById('success-eta').textContent = '35 - 45 mins';
        document.getElementById('order-success-modal').classList.remove('hidden');
        showToast('Order confirmed! Delicious food is on its way.', 'success');

    } catch (err) {
        console.error('Checkout error', err);
        showToast(err.message || 'Error occurred while placing order', 'error');
    } finally {
        checkoutBtn.disabled = false;
        checkoutBtn.innerHTML = `Proceed to Pay (₹<span id="cta-total-amount">${document.getElementById('bill-grand-total').textContent}</span>)`;
    }
}

// ==========================================================================
// PAST ORDERS DRAWER
// ==========================================================================

async function openOrdersHistoryDrawer() {
    if (!state.user || !state.user.token) {
        openAuthModal('login');
        return;
    }

    const drawer = document.getElementById('orders-drawer-overlay');
    const listContainer = document.getElementById('orders-list-container');
    const loading = document.getElementById('orders-loading');

    drawer.classList.remove('hidden');
    loading.classList.remove('hidden');
    listContainer.innerHTML = '';

    try {
        const res = await fetch(`${API_BASE}/api/orders/my`, { headers: authHeaders() });
        loading.classList.add('hidden');

        if (res.ok) {
            const orders = await res.json();
            if (orders && orders.length > 0) {
                listContainer.innerHTML = orders.map(o => `
                    <div class="order-history-card">
                        <div class="order-card-header">
                            <span class="order-num">${o.orderNumber || `ZMT-${o.id}`}</span>
                            <span class="order-status-badge ${o.status}">${o.status}</span>
                        </div>
                        <div class="order-card-date">${o.createdAt ? new Date(o.createdAt).toLocaleString() : 'Recent'}</div>
                        <div class="order-items-preview">
                            ${(o.items || []).map(i => `${i.quantity}x ${i.menuItemName || 'Dish'}`).join(', ') || 'Food items'}
                        </div>
                        <div class="order-card-footer">
                            <span>Delivery to: <strong>${o.deliveryAddress ? o.deliveryAddress.substring(0, 24) + '...' : 'Address'}</strong></span>
                            <strong>₹${Number(o.totalAmount || 0).toFixed(2)}</strong>
                        </div>
                    </div>
                `).join('');
            } else {
                listContainer.innerHTML = '<p class="text-center text-muted py-4">No past orders found. Place your first order today!</p>';
            }
        } else {
            listContainer.innerHTML = '<p class="text-center text-muted py-4">Unable to load orders. Please try again.</p>';
        }
    } catch (e) {
        loading.classList.add('hidden');
        listContainer.innerHTML = '<p class="text-center text-muted py-4">No orders loaded.</p>';
    }
}

// ==========================================================================
// EVENT LISTENERS & UI WIRING
// ==========================================================================

function setupEventListeners() {
    // Navigation Brand -> Home
    document.getElementById('brand-logo').addEventListener('click', () => {
        document.getElementById('view-restaurant-menu').classList.remove('active');
        document.getElementById('view-restaurants').classList.add('active');
        window.scrollTo({ top: 0, behavior: 'smooth' });
    });

    document.getElementById('back-to-home-btn').addEventListener('click', () => {
        document.getElementById('view-restaurant-menu').classList.remove('active');
        document.getElementById('view-restaurants').classList.add('active');
    });

    // Cart Navigation Button
    document.getElementById('cart-nav-btn').addEventListener('click', () => {
        openCartDrawer();
    });

    document.getElementById('close-cart-btn').addEventListener('click', closeCartDrawer);
    document.getElementById('cart-drawer-overlay').addEventListener('click', (e) => {
        if (e.target.id === 'cart-drawer-overlay') closeCartDrawer();
    });

    // Orders History Button
    document.getElementById('my-orders-nav-btn').addEventListener('click', openOrdersHistoryDrawer);
    document.getElementById('dropdown-orders-btn').addEventListener('click', (e) => {
        e.preventDefault();
        document.getElementById('user-dropdown').classList.remove('active');
        openOrdersHistoryDrawer();
    });
    document.getElementById('close-orders-btn').addEventListener('click', () => {
        document.getElementById('orders-drawer-overlay').classList.add('hidden');
    });
    document.getElementById('orders-drawer-overlay').addEventListener('click', (e) => {
        if (e.target.id === 'orders-drawer-overlay') {
            document.getElementById('orders-drawer-overlay').classList.add('hidden');
        }
    });

    // User Avatar Dropdown
    const avatarBtn = document.getElementById('user-avatar-btn');
    if (avatarBtn) {
        avatarBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            document.getElementById('user-dropdown').classList.toggle('active');
        });
    }

    document.addEventListener('click', () => {
        const dropdown = document.getElementById('user-dropdown');
        if (dropdown) dropdown.classList.remove('active');
    });

    document.getElementById('dropdown-logout-btn').addEventListener('click', (e) => {
        e.preventDefault();
        logout();
    });

    // Auth Modals
    document.getElementById('login-modal-btn').addEventListener('click', () => openAuthModal('login'));
    document.getElementById('register-modal-btn').addEventListener('click', () => openAuthModal('register'));
    document.getElementById('close-auth-modal').addEventListener('click', closeAuthModal);
    document.getElementById('auth-modal-overlay').addEventListener('click', (e) => {
        if (e.target.id === 'auth-modal-overlay') closeAuthModal();
    });

    document.getElementById('tab-btn-login').addEventListener('click', () => switchAuthTab('login'));
    document.getElementById('tab-btn-register').addEventListener('click', () => switchAuthTab('register'));

    // Auth Form Submits
    document.getElementById('login-form').addEventListener('submit', handleLogin);
    document.getElementById('register-form').addEventListener('submit', handleRegister);

    // Search Input
    const searchInput = document.getElementById('search-input');
    const clearBtn = document.getElementById('clear-search-btn');

    searchInput.addEventListener('input', (e) => {
        state.filters.searchQuery = e.target.value;
        if (e.target.value) {
            clearBtn.classList.remove('hidden');
        } else {
            clearBtn.classList.add('hidden');
        }
        applyFiltersAndRender();
    });

    clearBtn.addEventListener('click', () => {
        searchInput.value = '';
        state.filters.searchQuery = '';
        clearBtn.classList.add('hidden');
        applyFiltersAndRender();
    });

    // Category Items
    document.querySelectorAll('.category-item').forEach(item => {
        item.addEventListener('click', () => {
            document.querySelectorAll('.category-item').forEach(i => i.classList.remove('active'));
            item.classList.add('active');
            state.activeCategory = item.dataset.category;
            applyFiltersAndRender();
        });
    });

    // Filters
    const vegBtn = document.getElementById('filter-pure-veg');
    vegBtn.addEventListener('click', () => {
        state.filters.pureVeg = !state.filters.pureVeg;
        vegBtn.classList.toggle('active', state.filters.pureVeg);
        applyFiltersAndRender();
    });

    const ratingBtn = document.getElementById('filter-rating');
    ratingBtn.addEventListener('click', () => {
        state.filters.minRating = state.filters.minRating === 4.0 ? 0 : 4.0;
        ratingBtn.classList.toggle('active', state.filters.minRating > 0);
        applyFiltersAndRender();
    });

    const fastBtn = document.getElementById('filter-fast');
    fastBtn.addEventListener('click', () => {
        state.filters.fastDelivery = !state.filters.fastDelivery;
        fastBtn.classList.toggle('active', state.filters.fastDelivery);
        applyFiltersAndRender();
    });

    document.getElementById('sort-select').addEventListener('change', (e) => {
        state.filters.sortBy = e.target.value;
        applyFiltersAndRender();
    });

    document.getElementById('reset-filters-btn').addEventListener('click', () => {
        state.filters.searchQuery = '';
        state.filters.pureVeg = false;
        state.filters.minRating = 0;
        state.filters.fastDelivery = false;
        state.activeCategory = 'all';
        searchInput.value = '';
        clearBtn.classList.add('hidden');
        vegBtn.classList.remove('active');
        ratingBtn.classList.remove('active');
        fastBtn.classList.remove('active');
        document.querySelectorAll('.category-item').forEach(i => i.classList.toggle('active', i.dataset.category === 'all'));
        applyFiltersAndRender();
    });

    // Promo code apply
    document.getElementById('apply-promo-btn').addEventListener('click', () => {
        const input = document.getElementById('promo-code-input').value.trim().toUpperCase();
        if (input === 'WELCOME50') {
            state.appliedPromo = { code: 'WELCOME50', discount: 50, max: 150 };
            document.getElementById('promo-discount-badge').classList.remove('hidden');
            calculateBill();
            showToast('Coupon WELCOME50 applied! 50% discount added.', 'success');
        } else if (input.length > 0) {
            // Apply standard coupon
            state.appliedPromo = { code: input, discount: 20, max: 100 };
            document.getElementById('promo-discount-badge').classList.remove('hidden');
            calculateBill();
            showToast(`Coupon ${input} applied!`, 'success');
        }
    });

    document.getElementById('remove-promo-btn').addEventListener('click', () => {
        state.appliedPromo = null;
        document.getElementById('promo-code-input').value = '';
        document.getElementById('promo-discount-badge').classList.add('hidden');
        calculateBill();
        showToast('Coupon removed', 'info');
    });

    // Checkout Proceed
    document.getElementById('cart-checkout-btn').addEventListener('click', handleCheckout);

    // Success Modal actions
    document.getElementById('success-back-home-btn').addEventListener('click', () => {
        document.getElementById('order-success-modal').classList.add('hidden');
        document.getElementById('view-restaurant-menu').classList.remove('active');
        document.getElementById('view-restaurants').classList.add('active');
    });

    document.getElementById('view-order-details-btn').addEventListener('click', () => {
        document.getElementById('order-success-modal').classList.add('hidden');
        openOrdersHistoryDrawer();
    });

    // Initialize Free GPS Location Detection UI
    setupLocationDetectionUI();
}

// ==========================================================================
// REAL-TIME NEARBY RESTAURANTS (100% FREE VIA HTML5 GPS & OPENSTREETMAP)
// ==========================================================================

function setupLocationDetectionUI() {
    const locPicker = document.querySelector('.location-picker');
    const locInput = document.getElementById('delivery-location');

    if (locPicker && !document.getElementById('detect-location-btn')) {
        const detectBtn = document.createElement('button');
        detectBtn.type = 'button';
        detectBtn.id = 'detect-location-btn';
        detectBtn.className = 'detect-gps-btn';
        detectBtn.title = 'Detect your real-time nearby restaurants for free';
        detectBtn.innerHTML = '<i class="fa-solid fa-crosshairs"></i>';
        locPicker.appendChild(detectBtn);

        detectBtn.addEventListener('click', discoverRealNearbyRestaurants);
    }

    if (locInput) {
        locInput.style.cursor = 'pointer';
        locInput.title = 'Click to detect real-time nearby restaurants';
        locInput.addEventListener('click', () => {
            if (!state.userCoords) {
                discoverRealNearbyRestaurants();
            }
        });
    }
}

function getUserLocation() {
    return new Promise((resolve, reject) => {
        if (!navigator.geolocation) {
            reject(new Error("Geolocation is not supported by your browser"));
            return;
        }

        navigator.geolocation.getCurrentPosition(
            (position) => {
                resolve({
                    lat: position.coords.latitude,
                    lng: position.coords.longitude,
                    accuracy: position.coords.accuracy
                });
            },
            (error) => {
                let msg = "Location access was denied. Please allow location permissions in your browser.";
                if (error.code === error.TIMEOUT) msg = "Location detection timed out.";
                reject(new Error(msg));
            },
            {
                enableHighAccuracy: true,
                timeout: 10000,
                maximumAge: 60000
            }
        );
    });
}

async function getAddressFromCoordinates(lat, lng) {
    try {
        const res = await fetch(`https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=${lat}&longitude=${lng}&localityLanguage=en`);
        if (res.ok) {
            const data = await res.json();
            const locality = data.locality || data.city || data.principalSubdivision;
            const city = data.city || data.principalSubdivision;
            if (locality && city && locality !== city) {
                return `${locality}, ${city}`;
            }
            return locality || city || "Near your location";
        }
    } catch (e) {
        console.warn("Reverse geocode fallback", e);
    }
    return `${lat.toFixed(3)}, ${lng.toFixed(3)}`;
}

async function discoverRealNearbyRestaurants() {
    const locInput = document.getElementById('delivery-location');
    const detectBtn = document.getElementById('detect-location-btn');

    try {
        if (detectBtn) detectBtn.classList.add('loading');
        if (locInput) locInput.value = "Detecting your location...";
        showToast("Accessing device GPS...", "info");

        // 1. Get exact GPS coordinates (100% Free)
        const coords = await getUserLocation();
        state.userCoords = coords;

        // 2. Reverse geocode to city/locality name (100% Free)
        const address = await getAddressFromCoordinates(coords.lat, coords.lng);
        if (locInput) locInput.value = address;
        showToast(`Located at ${address}. Syncing real local restaurants...`, "info");

        // 3. Call backend to auto-seed real restaurants from OpenStreetMap & return them
        const res = await fetch(`${API_BASE}/api/restaurants/public/sync-real-nearby?lat=${coords.lat}&lng=${coords.lng}&radiusMeters=5000`, {
            method: 'POST'
        });

        if (res.ok) {
            const realRestaurants = await res.json();
            if (realRestaurants && realRestaurants.length > 0) {
                state.restaurants = realRestaurants;
                showToast(`Found ${realRestaurants.length} real restaurants in your neighborhood!`, "success");
            } else {
                // Fallback to existing nearby query
                const nearbyRes = await fetch(`${API_BASE}/api/restaurants/public/nearby?lat=${coords.lat}&lng=${coords.lng}&radius=15`);
                if (nearbyRes.ok) {
                    const fallbackData = await nearbyRes.json();
                    if (fallbackData && fallbackData.length > 0) {
                        state.restaurants = fallbackData;
                    }
                }
                showToast(`Showing nearest available restaurants with delivery to ${address}.`, "info");
            }
        } else {
            showToast("Showing nearby kitchens.", "info");
        }

        applyFiltersAndRender();
    } catch (err) {
        showToast(err.message || "Could not detect location", "error");
        if (locInput) locInput.value = "Downtown, City Center";
    } finally {
        if (detectBtn) detectBtn.classList.remove('loading');
    }
}


// ==========================================================================
// MODALS & DRAWERS
// ==========================================================================

function openCartDrawer() {
    renderCartDrawer();
    document.getElementById('cart-drawer-overlay').classList.remove('hidden');
}

function closeCartDrawer() {
    document.getElementById('cart-drawer-overlay').classList.add('hidden');
}

function openAuthModal(tab = 'login') {
    switchAuthTab(tab);
    document.getElementById('auth-modal-overlay').classList.remove('hidden');
}

function closeAuthModal() {
    document.getElementById('auth-modal-overlay').classList.add('hidden');
}

function switchAuthTab(tab) {
    const isLogin = tab === 'login';
    document.getElementById('tab-btn-login').classList.toggle('active', isLogin);
    document.getElementById('tab-btn-register').classList.toggle('active', !isLogin);
    document.getElementById('login-form').classList.toggle('active', isLogin);
    document.getElementById('register-form').classList.toggle('active', !isLogin);
}

// ==========================================================================
// LOGIN & REGISTER HANDLERS
// ==========================================================================

async function handleLogin(e) {
    e.preventDefault();
    const email = document.getElementById('login-email').value.trim();
    const password = document.getElementById('login-password').value;
    const btn = document.getElementById('submit-login-btn');

    btn.disabled = true;
    btn.textContent = 'Logging in...';

    try {
        const res = await fetch(`${API_BASE}/api/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        const data = await res.json().catch(() => ({}));

        if (res.ok) {
            saveUserToStorage({
                token: data.token,
                userId: data.userId,
                email: data.email,
                role: data.role,
                name: email.split('@')[0]
            });
            closeAuthModal();
            showToast('Welcome back! Login successful.', 'success');
        } else {
            const errorMsg = data.message || data.error || (data.fields ? Object.values(data.fields)[0] : null) || 'Invalid email or password';
            showToast(errorMsg, 'error');
        }
    } catch (err) {
        showToast('Login failed. Please verify credentials.', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Log In';
    }
}

async function handleRegister(e) {
    e.preventDefault();
    const name = document.getElementById('reg-name').value.trim();
    const email = document.getElementById('reg-email').value.trim();
    const password = document.getElementById('reg-password').value;
    const phone = document.getElementById('reg-phone').value.trim();
    const address = document.getElementById('reg-address').value.trim();
    const btn = document.getElementById('submit-reg-btn');

    btn.disabled = true;
    btn.textContent = 'Creating account...';

    try {
        const res = await fetch(`${API_BASE}/api/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                name,
                email,
                password,
                phone: phone || null,
                address: address || null,
                role: 'CUSTOMER'
            })
        });

        const data = await res.json().catch(() => ({}));

        if (res.ok) {
            showToast('Account created! Logging you in...', 'success');
            try {
                const loginRes = await fetch(`${API_BASE}/api/auth/login`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ email, password })
                });
                const loginData = await loginRes.json().catch(() => ({}));
                if (loginRes.ok) {
                    saveUserToStorage({
                        token: loginData.token,
                        userId: loginData.userId,
                        email: loginData.email,
                        role: loginData.role,
                        name: name || email.split('@')[0]
                    });
                    closeAuthModal();
                    showToast(`Welcome to Zomato, ${name || email.split('@')[0]}!`, 'success');
                    return;
                }
            } catch (loginErr) {
                console.warn('Auto-login error after registration:', loginErr);
            }
            showToast('Account created successfully! Please log in.', 'success');
            switchAuthTab('login');
            document.getElementById('login-email').value = email;
            document.getElementById('login-password').value = password;
        } else {
            const errorMsg = data.message || data.error || (data.fields ? Object.values(data.fields)[0] : null) || 'Registration failed';
            if (errorMsg.toLowerCase().includes('already registered')) {
                showToast('Email already registered! Switched to Log In tab.', 'info');
                switchAuthTab('login');
                document.getElementById('login-email').value = email;
                document.getElementById('login-password').focus();
            } else {
                showToast(errorMsg, 'error');
            }
        }
    } catch (err) {
        showToast('Error registering account', 'error');
    } finally {
        btn.disabled = false;
        btn.textContent = 'Create Account';
    }
}

// ==========================================================================
// MOCK DATA FALLBACKS (Ensure UI is always rich and beautiful)
// ==========================================================================

function getMockRestaurants() {
    return [
        {
            id: 1,
            name: "Royal Biryani House",
            description: "Authentic Dum Biryani, Kebabs & Mughlai Delicacies",
            cuisineType: "Biryani, North Indian, Mughlai",
            address: "Brigade Road, City Center",
            rating: 4.6,
            avgDeliveryTime: 25,
            minOrderAmount: 149,
            imageUrl: "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=500&auto=format&fit=crop&q=80",
            open: true
        },
        {
            id: 2,
            name: "La Piazza Woodfired Pizza",
            description: "Handcrafted artisan sourdough pizzas & fresh pasta",
            cuisineType: "Pizza, Italian, Fast Food",
            address: "Indiranagar 100ft Road",
            rating: 4.5,
            avgDeliveryTime: 30,
            minOrderAmount: 199,
            imageUrl: "https://images.unsplash.com/photo-1513104890138-7c749659a591?w=500&auto=format&fit=crop&q=80",
            open: true
        },
        {
            id: 3,
            name: "The Burger Republic",
            description: "Juicy smash burgers, crispy tenders and milkshakes",
            cuisineType: "Burger, Fast Food, American",
            address: "Koramangala 5th Block",
            rating: 4.4,
            avgDeliveryTime: 20,
            minOrderAmount: 99,
            imageUrl: "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=500&auto=format&fit=crop&q=80",
            open: true
        },
        {
            id: 4,
            name: "Golden Dragon Wok",
            description: "Traditional Dim Sums, Hakka Noodles and Sichuan Gravies",
            cuisineType: "Chinese, Asian, Momos",
            address: "MG Road Metro Station Area",
            rating: 4.3,
            avgDeliveryTime: 35,
            minOrderAmount: 150,
            imageUrl: "https://images.unsplash.com/photo-1541696432-82c6da8ce7bf?w=500&auto=format&fit=crop&q=80",
            open: true
        },
        {
            id: 5,
            name: "Shree Krishna Veg Pure",
            description: "Pure vegetarian South Indian breakfast & North Indian Thalis",
            cuisineType: "Pure Veg, South Indian, North Indian",
            address: "Jayanagar 4th Block",
            rating: 4.7,
            avgDeliveryTime: 20,
            minOrderAmount: 80,
            imageUrl: "https://images.unsplash.com/photo-1589301760014-d929f3979dbc?w=500&auto=format&fit=crop&q=80",
            open: true
        },
        {
            id: 6,
            name: "Sweet Tooth Desserts",
            description: "Molten lava cakes, cheesecakes & gourmet churros",
            cuisineType: "Dessert, Bakery, Waffles",
            address: "HSR Layout Sector 3",
            rating: 4.8,
            avgDeliveryTime: 25,
            minOrderAmount: 120,
            imageUrl: "https://images.unsplash.com/photo-1551024601-bec78aea704b?w=500&auto=format&fit=crop&q=80",
            open: true
        }
    ];
}

function getMockMenuItems(restaurantId) {
    const menus = {
        1: [
            { id: 101, name: "Hyderabadi Chicken Dum Biryani", price: 299, veg: false, category: "Biryani", description: "Long grain basmati rice layered with succulent marinated chicken and aromatic saffron spices.", imageUrl: "https://images.unsplash.com/photo-1563379091339-03b21ab4a4f8?w=300&auto=format&fit=crop&q=80" },
            { id: 102, name: "Paneer Tikka Biryani", price: 249, veg: true, category: "Biryani", description: "Clay oven roasted cottage cheese tossed with spiced basmati rice and mint dip.", imageUrl: "https://images.unsplash.com/photo-1645177628172-a94c1f96e6db?w=300&auto=format&fit=crop&q=80" },
            { id: 103, name: "Murgh Malai Kebab (6 Pcs)", price: 260, veg: false, category: "Starters", description: "Tender chicken morsels marinated in fresh cream, cheese and mild green cardamom.", imageUrl: "https://images.unsplash.com/photo-1599488615731-7e5c2823ff28?w=300&auto=format&fit=crop&q=80" },
            { id: 104, name: "Mirchi Ka Salan", price: 80, veg: true, category: "Sides", description: "Traditional peanut, sesame and green chili gravy served hot.", imageUrl: "https://images.unsplash.com/photo-1589302168068-964664d93dc0?w=300&auto=format&fit=crop&q=80" }
        ],
        2: [
            { id: 201, name: "Margherita Basilico Pizza (10\")", price: 340, veg: true, category: "Pizza", description: "San Marzano tomato sauce, fresh mozzarella fior di latte and hand-picked fresh basil.", imageUrl: "https://images.unsplash.com/photo-1574071318508-1cdbab80d002?w=300&auto=format&fit=crop&q=80" },
            { id: 202, name: "Smoked Pepperoni Feast (10\")", price: 420, veg: false, category: "Pizza", description: "Spicy Italian cured pepperoni, mozzarella cheese and chili-infused organic honey.", imageUrl: "https://images.unsplash.com/photo-1628840042765-356cda07504e?w=300&auto=format&fit=crop&q=80" },
            { id: 203, name: "Creamy Truffle Penne", price: 380, veg: true, category: "Pasta", description: "Penne tossed in wild mushroom reduction and aromatic white truffle oil.", imageUrl: "https://images.unsplash.com/photo-1621996346565-e3d5d6281699?w=300&auto=format&fit=crop&q=80" }
        ],
        3: [
            { id: 301, name: "Classic American Cheeseburger", price: 189, veg: false, category: "Burgers", description: "Double smashed patty, melted sharp cheddar, caramelized onions and secret house sauce.", imageUrl: "https://images.unsplash.com/photo-1568901346375-23c9450c58cd?w=300&auto=format&fit=crop&q=80" },
            { id: 302, name: "Crispy Peri Peri Veg Burger", price: 149, veg: true, category: "Burgers", description: "Crunchy herb potato and peas patty topped with spicy peri-peri garlic mayo.", imageUrl: "https://images.unsplash.com/photo-1550547660-d9450f859349?w=300&auto=format&fit=crop&q=80" },
            { id: 303, name: "Loaded Cheese Fries", price: 129, veg: true, category: "Sides", description: "Golden crispy skin-on fries drowned in warm melted cheese sauce and jalapenos.", imageUrl: "https://images.unsplash.com/photo-1576107232684-1279f3908594?w=300&auto=format&fit=crop&q=80" }
        ]
    };

    return menus[restaurantId] || [
        { id: 901, name: "Chef's Special Gourmet Platter", price: 320, veg: true, category: "Specialties", description: "Handcrafted assortment of the chef's most celebrated recipes.", imageUrl: "https://images.unsplash.com/photo-1546069901-ba9599a7e63c?w=300&auto=format&fit=crop&q=80" },
        { id: 902, name: "Crispy Spring Rolls", price: 160, veg: true, category: "Starters", description: "Crisp golden wrappers filled with wok-tossed farm fresh vegetables and sweet chili dip.", imageUrl: "https://images.unsplash.com/photo-1544025162-d76694265947?w=300&auto=format&fit=crop&q=80" },
        { id: 903, name: "Fresh Mint Mojito", price: 110, veg: true, category: "Beverages", description: "Refreshing blend of fresh garden mint, Persian lime, cane sugar and sparkling soda.", imageUrl: "https://images.unsplash.com/photo-1551024709-8f23befc6f87?w=300&auto=format&fit=crop&q=80" }
    ];
}
