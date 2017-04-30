(ns orcpub.character-builder
  (:require [goog.dom :as gdom]
            [goog.string :as gs]
            [goog.labs.userAgent.device :as device]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as s]
            [clojure.set :as sets]

            [orcpub.common :as common]
            [orcpub.constants :as const]
            [orcpub.template :as t]
            [orcpub.entity :as entity]
            [orcpub.entity-spec :as es]
            [orcpub.dice :as dice]
            [orcpub.modifiers :as mod]
            [orcpub.dnd.e5.character :as char5e]
            [orcpub.dnd.e5.modifiers :as mod5e]
            [orcpub.dnd.e5.options :as opt5e]
            [orcpub.dnd.e5.template :as t5e]
            [orcpub.dnd.e5.spells :as spells]
            [orcpub.dnd.e5.weapons :as weapon5e]
            [orcpub.dnd.e5.armor :as armor5e]
            [orcpub.dnd.e5.magic-items :as mi5e]
            [orcpub.dnd.e5.display :as disp5e]
            [orcpub.dnd.e5.equipment :as equip5e]
            [orcpub.dnd.e5.skills :as skill5e]
            [orcpub.dnd.e5.events :as events5e]
            [orcpub.dnd.e5.db :as db5e]
            [orcpub.pdf-spec :as pdf-spec]

            [clojure.spec :as spec]
            [clojure.spec.test :as stest]

            [reagent.core :as r]
            [re-frame.core :refer [subscribe dispatch dispatch-sync]]))

(def print-disabled? false)
(def print-enabled? (and (not print-disabled?)
                         (s/starts-with? js/window.location.href "http://localhost")))

(defn stop-propagation [e]
  (.stopPropagation e))

(defn stop-prop-fn [func]
  (fn [e]
    (func e)
    (stop-propagation e)))

(defn update-option [template entity path update-fn]
  (let [entity-path (entity/get-entity-path template entity path)]
    (try (update-in entity entity-path update-fn)
         (catch js/Object e (do (js/console.log "ERRO" entity path entity-path)
                                (throw "EROR"))))))

#_(def stored-char-str (.getItem js/window.localStorage "char-meta"))
#_(defn remove-stored-char [stored-char-str & [more-info]]
  (js/console.warn (str "Invalid char-meta: " stored-char-str more-info))
  (.removeItem js/window.localStorage "char-meta"))
#_(def stored-char (if stored-char-str (try (let [v (reader/read-string stored-char-str)]
                                            (if (spec/valid? ::entity/raw-entity v)
                                              v
                                              (remove-stored-char stored-char-str (str (spec/explain-data ::entity/raw-entity v)))))
                                          (catch js/Object
                                              e
                                            (remove-stored-char stored-char-str)))))

#_(defonce history
  (r/atom (list (:character @app-state))))

#_(defn undo! []
  (when (> (count @history) 1)
    (swap! history pop)
    (swap! app-state events5e/set-character [(peek @history)])))

#_(if print-enabled?
  (add-watch app-state :log (fn [k r os ns]
                              (js/console.log "OLD" (clj->js os))
                              (js/console.log "NEW" (clj->js ns)))))

#_(add-watch app-state
           :local-storage
           (fn [k r os ns]
             (.setItem js/window.localStorage "char-meta" (str (:character ns)))))

#_(add-watch app-state
           :history
           (fn [k r os ns]
             (if (not= (:character ns) (peek @history))
               (swap! history conj (:character ns)))))

(defn selector-id [path]
  (s/join "--" (map name path)))

(defn realize-char [built-char]
  (reduce-kv
   (fn [m k v]
     (let [realized-value (es/entity-val built-char k)]
       (if (fn? realized-value)
         m
         (assoc m k realized-value))))
   (sorted-map)
   built-char))

(defn print-char [built-char]
  (cljs.pprint/pprint
   (dissoc (realize-char built-char) ::es/deps)))

(defn svg-icon [icon-name & [size]]
  (let [size (or size 32)]
    [:img
     {:class-name (str "h-" size " w-" size)
      :src (str "image/" icon-name ".svg")}]))

(defn display-section [title icon-name value & [list?]]
  [:div.m-t-20
   [:div.flex.align-items-c
    (if icon-name (svg-icon icon-name))
    [:span.m-l-5.f-s-16.f-w-600 title]]
   [:div {:class-name (if list? "m-t-0" "m-t-4")}
    [:span.f-s-24.f-w-600
     value]]])

(defn list-display-section [title image-name values]
  (if (seq values)
    (display-section
     title
     image-name
     [:span.m-t-5.f-s-14.f-w-n.i
      (s/join
       ", "
       values)]
     true)))

(defn svg-icon-section [title icon-name content]
  [:div.m-t-20
   [:span.f-s-16.f-w-600 title]
   [:div.flex.align-items-c
    (svg-icon icon-name)
    [:div.f-s-24.m-l-10.f-w-b content]]])

(defn armor-class-section [armor-class armor-class-with-armor equipped-armor]
  (let [equipped-armor-full (mi5e/equipped-armor-details equipped-armor)
        shields (filter #(= :shield (:type %)) equipped-armor-full)
        armor (filter #(not= :shield (:type %)) equipped-armor-full)
        display-rows (for [a (conj armor nil)
                           shield (conj shields nil)]
                       (let [el (if (= nil a shield) :span :div)]
                         ^{:key (common/name-to-kw (str (:name a) (:name shield)))}
                        [el
                         [el
                          [:span.f-s-24.f-w-b (armor-class-with-armor a shield)]
                          [:span.display-section-qualifier-text (str "("
                                                                     (if a (:name a) "unarmored")
                                                                     (if shield (str " & " (:name shield)))
                                                                     ")")]]]))]
    (svg-icon-section
     "Armor Class"
     "checked-shield"
     [:span
       (first display-rows)
       [:div
        (doall (rest display-rows))]])))

(defn speed-section [built-char all-armor]
  (let [speed (char5e/base-land-speed built-char)
        speed-with-armor (char5e/land-speed-with-armor built-char)
        unarmored-speed-bonus (char5e/unarmored-speed-bonus built-char)
        equipped-armor (char5e/normal-armor-inventory built-char)]
    [svg-icon-section
     "Speed"
     "walking-boot"
     [:span
      [:span
       [:span (+ (or unarmored-speed-bonus 0)
                 (if speed-with-armor
                   (speed-with-armor nil)
                   speed))]
       (if (or unarmored-speed-bonus
               speed-with-armor)
         [:span.display-section-qualifier-text "(unarmored)"])]
      (if speed-with-armor
        [:div
         (doall
          (map
           (fn [[armor-kw _]]
             (let [armor (mi5e/all-armor-map armor-kw)
                   speed (speed-with-armor armor)]
               ^{:key armor-kw}
               [:div
                [:div
                 [:span speed]
                 [:span.display-section-qualifier-text (str "(" (:name armor) " armor)")]]]))
           (dissoc all-armor :shield)))]
        (if unarmored-speed-bonus
          [:div
           [:span
            [:span speed]
            [:span.display-section-qualifier-text "(armored)"]]]))
      (let [swim-speed (char5e/base-swimming-speed built-char)]
        (if swim-speed
          [:div [:span swim-speed] [:span.display-section-qualifier-text "(swim)"]]))
      (let [flying-speed (char5e/base-flying-speed built-char)]
        (if flying-speed
          [:div [:span flying-speed] [:span.display-section-qualifier-text "(fly)"]]))]]))

(defn list-item-section [list-name icon-name items & [name-fn]]
  [list-display-section list-name icon-name
   (map
    (fn [item]
      ((or name-fn :name) item))
    items)])

(defn compare-spell [spell-1 spell-2]
  (let [key-fn (juxt :key :ability)]
    (compare (key-fn spell-1) (key-fn spell-2))))

(defn spells-known-section [spells-known spell-slots]
  [display-section "Spells Known" "spell-book"
   [:div.f-s-14.flex.flex-wrap
    (doall
     (map
      (fn [[level spells]]
        ^{:key level}
        [:div.m-t-10.w-200
         [:span.f-w-b (str (if (zero? level) "Cantrip" (str "Level " level)) (if (pos? level)
                                                                                 (str " (" (spell-slots level) " slots)")))]
         [:div.i.f-w-n
          (doall
           (map-indexed
            (fn [i spell]
              (let [spell-data (spells/spell-map (:key spell))]
                ^{:key i}
                [:div
                 (str
                  (:name (spells/spell-map (:key spell)))
                  " ("
                  (s/join
                   ", "
                   (remove
                    nil?
                    [(if (:ability spell) (s/upper-case (name (:ability spell))))
                     (if (:qualifier spell) (:qualifier spell))]))
                
                  ")")]))
            (into
             (sorted-set-by compare-spell)
             (filter
              (fn [{k :key}]
                (spells/spell-map k))
              spells))))]])
      (filter
       (comp seq second)
       spells-known)))]])

(defn equipment-section [title icon-name equipment equipment-map]
  [list-display-section title icon-name
   (map
    (fn [[equipment-kw {item-qty :quantity equipped? :equipped? :as num}]]
      (str (disp5e/equipment-name equipment-map equipment-kw)
           " (" (or item-qty num) ")"))
    equipment)])

(defn add-links [desc]
  desc
  (let [{:keys [abbr url]} (some (fn [[_ source]]
                                   (if (and (:abbr source)
                                            (re-matches (re-pattern (str ".*" (:abbr source) ".*")) desc))
                             source))
                 disp5e/sources)
        [before after] (if abbr (s/split desc (re-pattern abbr)))]
    (if abbr
      [:span
       [:span before]
       [:a {:href url :target :_blank} abbr]
       [:span after]]
      desc)))

(defn attacks-section [attacks]
  (if (seq attacks)
    (display-section
     "Attacks"
     "pointy-sword"
     [:div.f-s-14
      (doall
       (map
        (fn [{:keys [name area-type description damage-die damage-die-count damage-type save save-dc] :as attack}]
          ^{:key name}
          [:p.m-t-10
           [:span.f-w-600.i name "."]
           [:span.f-w-n.m-l-10 (add-links (common/sentensize (disp5e/attack-description attack)))]])
        attacks))])))

(defn actions-section [title icon-name actions]
  (if (seq actions)
    (display-section
     title icon-name
     [:div.f-s-14
      (doall
       (map
        (fn [action]
          ^{:key action}
          [:p.m-t-10
           [:span.f-w-600.i (:name action) "."]
           [:span.f-w-n.m-l-10 (add-links (common/sentensize (disp5e/action-description action)))]])
        (sort-by :name actions)))])))

(defn prof-name [prof-map prof-kw]
  (or (-> prof-kw prof-map :name) (common/kw-to-name prof-kw)))


(defn character-display []
  (let [built-char @(subscribe [:built-character])
        race (char5e/race built-char)
        subrace (char5e/subrace built-char)
        alignment (char5e/alignment built-char)
        background (char5e/background built-char)
        classes (char5e/classes built-char)
        levels (char5e/levels built-char)
        darkvision (char5e/darkvision built-char)
        skill-profs (char5e/skill-proficiencies built-char)
        tool-profs (char5e/tool-proficiencies built-char)
        weapon-profs (char5e/weapon-proficiencies built-char)
        armor-profs (char5e/armor-proficiencies built-char)
        resistances (char5e/damage-resistances built-char)
        immunities (char5e/damage-immunities built-char)
        condition-immunities (char5e/condition-immunities built-char)
        languages (char5e/languages built-char)
        abilities (char5e/ability-values built-char)
        ability-bonuses (char5e/ability-bonuses built-char)
        armor-class (char5e/base-armor-class built-char)
        armor-class-with-armor (char5e/armor-class-with-armor built-char)
        armor (char5e/normal-armor-inventory built-char)
        magic-armor (char5e/magic-armor-inventory built-char)
        all-armor (merge magic-armor armor)
        spells-known (char5e/spells-known built-char)
        spell-slots (char5e/spell-slots built-char)
        weapons (char5e/normal-weapons-inventory built-char)
        magic-weapons (char5e/magic-weapons-inventory built-char)
        equipment (char5e/normal-equipment-inventory built-char)
        magic-items (char5e/magical-equipment-inventory built-char)
        traits (char5e/traits built-char)
        attacks (char5e/attacks built-char)
        bonus-actions (char5e/bonus-actions built-char)
        reactions (char5e/reactions built-char)
        actions (char5e/actions built-char)
        image-url (char5e/image-url built-char)]
    [:div
     [:div.f-s-24.f-w-600.m-b-16.text-shadow.flex
      [:span
       [:span race]
       [:div.f-s-12.m-t-5.opacity-6 subrace]]
      (if (seq levels)
        [:span.m-l-10.flex
         (map-indexed
          (fn [i v]
            (with-meta v {:key i}))
          (interpose
           [:span.m-l-5.m-r-5 "/"]
           (map
            (fn [cls-key]
              (let [{:keys [class-name class-level subclass]} (levels cls-key)]
                [:span
                 [:span (str class-name " (" class-level ")")]
                 [:div.f-s-12.m-t-5.opacity-6 (if subclass (common/kw-to-name subclass true))]]))
            classes)))])]
     [:div.details-columns
      [:div.flex-grow-1.flex-basis-50-p
       [:div.w-100-p.t-a-c
        [:div.flex.justify-cont-s-b.p-10
         (doall
          (map
           (fn [k]
             ^{:key k}
             [:div
              (t5e/ability-icon k 32)
              [:div.f-s-20.uppercase (name k)]
              [:div.f-s-24.f-w-b (abilities k)]
              [:div.f-s-12.opacity-5.m-b--2.m-t-2 "mod"]
              [:div.f-s-18 (common/bonus-str (ability-bonuses k))]])
           char5e/ability-keys))]]
       [:div.flex
        [:div.w-50-p
         [:img.character-image.w-100-p.m-b-20 {:src (or image-url "image/barbarian.png")}]]
        [:div.w-50-p
         (if background [svg-icon-section "Background" "ages" [:span.f-s-18.f-w-n background]])
         (if alignment [svg-icon-section "Alignment" "yin-yang" [:span.f-s-18.f-w-n alignment]])
         [armor-class-section armor-class armor-class-with-armor all-armor]
         [svg-icon-section "Hit Points" "health-normal" (char5e/max-hit-points built-char)]
         [speed-section built-char all-armor]
         [svg-icon-section "Darkvision" "night-vision" (if darkvision (str darkvision " ft.") "--")]
         [svg-icon-section "Initiative" "sprint" (mod/bonus-str (char5e/initiative built-char))]
         [display-section "Proficiency Bonus" nil (mod/bonus-str (char5e/proficiency-bonus built-char))]
         [svg-icon-section "Passive Perception" "awareness" (char5e/passive-perception built-char)]
         (let [num-attacks (char5e/number-of-attacks built-char)]
           (if (> num-attacks 1)
             [display-section "Number of Attacks" nil num-attacks]))
         (let [criticals (char5e/critical-hit-values built-char)
               min-crit (apply min criticals)
               max-crit (apply max criticals)]
           (if (not= min-crit max-crit)
             (display-section "Critical Hit" nil (str min-crit "-" max-crit))))
         [:div
          [list-display-section
           "Save Proficiencies" "dodging"
           (map (comp s/upper-case name) (char5e/saving-throws built-char))]
          (let [save-advantage (char5e/saving-throw-advantages built-char)]
            [:ul.list-style-disc.m-t-5
             (doall
              (map-indexed
               (fn [i {:keys [abilities types]}]
                 ^{:key i}
                 [:li (str "advantage on "
                           (common/list-print (map (comp s/lower-case :name opt5e/abilities-map) abilities))
                           " saves against "
                           (common/list-print
                            (map #(let [condition (opt5e/conditions-map %)]
                                    (cond
                                      condition (str "being " (s/lower-case (:name condition)))
                                      (keyword? %) (name %)
                                      :else %))
                                 types)))])
               save-advantage))])]]]]
      [:div.flex-grow-1.flex-basis-50-p
       [list-display-section "Skill Proficiencies" "juggler"
        (let [skill-bonuses (char5e/skill-bonuses built-char)]
          (map
           (fn [[skill-kw bonus]]
             (str (s/capitalize (name skill-kw)) " " (mod/bonus-str bonus)))
           (filter (fn [[k bonus]]
                     (not= bonus (ability-bonuses (:ability (skill5e/skills-map k)))))
                   skill-bonuses)))]
       [list-item-section "Languages" "lips" languages (partial prof-name opt5e/language-map)]
       [list-item-section "Tool Proficiencies" "stone-crafting" tool-profs (partial prof-name equip5e/tools-map)]
       [list-item-section "Weapon Proficiencies" "bowman" weapon-profs (partial prof-name weapon5e/weapons-map)]
       [list-item-section "Armor Proficiencies" "mailed-fist" armor-profs (partial prof-name armor5e/armor-map)]
       [list-item-section "Damage Resistances" "surrounded-shield" resistances name]
       [list-item-section "Damage Immunities" nil immunities name]
       [list-item-section "Condition Immunities" nil condition-immunities (fn [{:keys [condition qualifier]}]
                                                                        (str (name condition)
                                                                             (if qualifier (str " (" qualifier ")"))))]
       (if (seq spells-known) [spells-known-section spells-known spell-slots])
       [equipment-section "Weapons" "plain-dagger" (concat magic-weapons weapons) mi5e/all-weapons-map]
       [equipment-section "Armor" "breastplate" (merge magic-armor armor) mi5e/all-armor-map]
       [equipment-section "Equipment" "backpack" (concat magic-items
                                                         equipment
                                                         (map
                                                          (juxt :name identity)
                                                          (es/entity-val built-char :custom-equipment))) mi5e/all-equipment-map]
       [attacks-section attacks]
       [actions-section "Actions" "beams-aura" actions]
       [actions-section "Bonus Actions" "run" bonus-actions]
       [actions-section "Reactions" "van-damme-split" reactions]
       [actions-section "Features, Traits, and Feats" "vitruvian-man" traits]]]]))

(defn get-template-from-props [x]
  (get (.-argv (.-props x)) 6))

(defn help-section [help]
  [:div.m-t-10.f-w-n
   (if (string? help)
     (doall
      (map-indexed
       (fn [i para]
         ^{:key i}
         [:p.m-b-5.m-b-0-last para])
       (s/split help #"\n")))
     help)])

(defn expand-button [expand-text collapse-text expanded?]
  [:span.flex.pointer.align-items-c.justify-cont-end
   {:on-click (fn [e]
                (swap! expanded? not)
                (stop-propagation e))}
   [:span.underline.orange.p-0.m-r-2 (if @expanded? expand-text collapse-text)]
   [:i.fa.orange
    {:class-name (if @expanded? "fa-angle-up" "fa-angle-down")}]])

(defn show-info-button [expanded?]
  [:div.f-w-n.m-l-5 [expand-button "hide info" "show info" expanded?]])

(defn multiselection? [{:keys [::t/multiselect? ::t/min ::t/max]}]
  (or multiselect?
      (and (> min 1)
           (= min max))
      (nil? max)))

(defn get-event-value [e]
  (.-value (.-target e)))

(defn character-state-path [path]
  (concat [:character] path))

(defn character-field [entity-values prop-name type & [cls-str]]
  [type {:class-name (str "input " cls-str)
         :type :text
         :value (get entity-values prop-name)
         :on-change #(dispatch [:update-value-field prop-name (get-event-value %)])}])

(defn character-input [entity-values prop-name & [cls-str]]
  (character-field entity-values prop-name :input cls-str))

(defn character-textarea [entity-values prop-name & [cls-str]]
  (character-field entity-values prop-name :textarea cls-str))

(defn class-level-selector []
  (let [expanded? (r/atom false)]
    (fn [i key selected-class options unselected-classes-set multiclass-options]
      (let [options-map (zipmap (map ::t/key options) options)
            class-template-option (options-map key)
            path [:class-levels key]]
        [:div.m-b-5
         {:class-name (if @expanded? "b-1 b-rad-5 p-5")}
         [:div.flex.align-items-c
          [:select.builder-option.builder-option-dropdown.flex-grow-1.m-t-0
           {:value key
            :on-change (fn [e] (let [new-key (keyword (.. e -target -value))]
                                 (dispatch [:set-class new-key i options-map])))}
           (doall
            (map
             (fn [{:keys [::t/key ::t/name] :as option}]
               ^{:key key}
               [:option.builder-dropdown-item
                {:value key}
                name])
             (filter
              #(or (= key (::t/key %))
                   (unselected-classes-set (::t/key %)))
              (if (zero? i)
                options
                multiclass-options))))]
          (if (::t/help class-template-option)
            [show-info-button expanded?])
          (let [selected-levels (get-in selected-class [::entity/options :levels])
                levels-selection (some #(if (= :levels (::t/key %)) %) (::t/selections class-template-option))
                available-levels (::t/options levels-selection)
                last-level-key (::entity/key (last selected-levels))]
            [:select.builder-option.builder-option-dropdown.m-t-0.m-l-5.w-100
             {:value last-level-key
              :on-change
              (fn [e]
                (let [new-highest-level-str (.. e -target -value)
                      new-highest-level (js/parseInt (last (s/split new-highest-level-str #"-")))]
                  (dispatch [:set-class-level i new-highest-level])))}
             (doall
              (map-indexed
               (fn [i {level-key ::t/key}]
                 ^{:key level-key}
                 [:option.builder-dropdown-item
                  {:value level-key}
                  (inc i)])
               available-levels))])
          [:i.fa.fa-minus-circle.orange.f-s-16.m-l-5.pointer
           {:on-click (fn [_] (dispatch [:delete-class key]))}]]
         (if @expanded?
           [:div.m-t-5.m-b-10 (::t/help class-template-option)])]))))

(defn class-level-data [option]
  (let [levels (some
                (fn [s]
                  (if (= :levels (::t/key s))
                    s))
                (::t/selections option))]
    (assoc
     (select-keys option [::t/key ::t/name ::t/help ::t/associated-options])
     ::t/selections
     [{::t/key (::t/key levels)
       ::t/options (map #(select-keys % [::t/key]) (::t/options levels))}])))

(defn meets-prereqs? [built-char option]
  (every?
   (fn [{:keys [::t/prereq-fn] :as prereq}]
     (if prereq-fn
       (prereq-fn built-char)
       (js/console.warn "NO PREREQ_FN" (::t/name option) prereq)))
   (::t/prereqs option)))

(defn class-levels-selector [{:keys [character selection built-char]}]
  (let [options (::t/options selection)
        selected-classes (get-in character [::entity/options :class])
        unselected-classes (remove
                            (into #{} (map ::entity/key selected-classes))
                            (map ::t/key options))
        unselected-classes-set (set unselected-classes)
        remaining-classes (filter
                           (fn [option]
                             (and
                              (unselected-classes-set (::t/key option))
                              (meets-prereqs? built-char option)))
                           options)]
    [:div
     [:div
      (doall
       (map-indexed
        (fn [i {:keys [::entity/key] :as selected-class}]
          (let [multiclass-options (filter
                                    (partial meets-prereqs? built-char)
                                    options)]
            ^{:key key}
            [class-level-selector i key selected-class (map class-level-data options) unselected-classes-set (map class-level-data multiclass-options)]))
        selected-classes))]
     (if (seq remaining-classes)
       [:div.orange.p-5.underline.pointer
        [:i.fa.fa-plus-circle.orange.f-s-16]
        [:span.m-l-5
         {:on-click
          (fn [_]
            (let [first-unselected (::t/key (first remaining-classes))]
              (dispatch [:add-class first-unselected])))}
         "Add Class"]])]))

(defn checkbox [selected? disable?]
  [:i.fa.fa-check.f-s-14.bg-white.orange-shadow.m-r-10
   {:class-name (str (if selected? "black slight-text-shadow" "transparent")
                     " "
                     (if disable?
                       "opacity-5"))}])

(defn inventory-item []
  (let [expanded? (r/atom false)]
    (fn [{:keys [selection-key
                 item-key
                 item-name
                 item-qty
                 item-description
                 equipped?
                 i
                 qty-input-width
                 check-fn
                 qty-change-fn
                 remove-fn]}]
      [:div.p-5
       [:div.f-w-b.flex.align-items-c
        [:div.pointer.m-l-5
         {:on-click check-fn}
         (checkbox equipped? false)]
        [:div.flex-grow-1 item-name]
        (if item-description [:div.w-60 [show-info-button expanded?]])
        [:input.input.m-l-5.m-t-0.
         {:class-name (str "w-" (or qty-input-width 60))
          :type :number
          :value item-qty
          :on-change qty-change-fn}]
        [:i.fa.fa-minus-circle.orange.f-s-16.m-l-5.pointer
         {:on-click remove-fn}]]
       (if @expanded? [:div.m-t-5 item-description])])))

(defn inventory-selector [item-map qty-input-width {:keys [character selection]} & [custom-equipment-key]]
  (let [{:keys [::t/key ::t/options]} selection
        selected-items (get-in character [::entity/options key])
        selected-keys (into #{} (map ::entity/key selected-items))]
    [:div
     [:select.builder-option.builder-option-dropdown
      {:value ""
       :on-change
       (fn [e]
         (let [kw (keyword (.. e -target -value))]
           (dispatch [:add-inventory-item key kw])))}
      [:option.builder-dropdown-item
       {:value ""
        :disabled true}
       "<select to add>"]
      (doall
       (map
        (fn [{:keys [::t/key ::t/name]}]
          ^{:key key}
          [:option.builder-dropdown-item
           {:value key}
           name])
        (sort-by ::t/name (remove #(selected-keys (::t/key %)) options))))]
     (if (seq selected-items)
       [:div.flex.f-s-12.opacity-5.m-t-10.justify-cont-s-b
        [:div.m-r-10 "Equipped?"]
        [:div.m-r-30 "Quantity"]])
     [:div
      (doall
       (map-indexed
        (fn [i {item-key ::entity/key {item-qty :quantity equipped? :equipped? item-name :name} ::entity/value}]
          (let [item (item-map item-key)
                item-name (or item-name (:name item))
                item-description (:description item)]
            ^{:key item-key}
            [inventory-item {:selection-key key
                             :item-key item-key
                             :item-name item-name
                             :item-qty item-qty
                             :item-description item-description
                             :equipped? equipped?
                             :i i
                             :qty-input-width qty-input-width
                             :check-fn (fn [_]
                                         (dispatch [:toggle-inventory-item-equipped key i]))
                             :qty-change-fn (fn [e]
                                              (let [qty (.. e -target -value)]
                                                (dispatch [:change-inventory-item-quantity key i qty])))
                             :remove-fn (fn [_]
                                          (dispatch [:remove-inventory-item key item-key]))}]))
        selected-items))]
     (if custom-equipment-key
       [:div
        (doall
         (map-indexed
          (fn [i {:keys [name quantity equipped?]}]
            (let [item-key (common/name-to-kw name)]
              ^{:key item-key}
              [inventory-item {:selection-key custom-equipment-key
                               :item-key item-key
                               :item-name name
                               :item-qty quantity
                               :equipped? equipped?
                               :i i
                               :qty-input-width qty-input-width
                               :check-fn (fn [_]
                                           (dispatch [:toggle-custom-inventory-item-equipped custom-equipment-key i]))
                               :qty-change-fn (fn [e]
                                                (let [qty (.. e -target -value)]
                                                  (dispatch [:change-custom-inventory-item-quantity custom-equipment-key i qty])))
                               :remove-fn (fn [_]
                                            (dispatch [:remove-custom-inventory-item custom-equipment-key name]))}]))
          (get-in character [::entity/values custom-equipment-key])))])]))

(defn option-selector-base []
  (let [expanded? (r/atom false)]
    (fn [{:keys [name key help selected? selectable? option-path select-fn content explanation-text icon classes multiselect? disable-checkbox?]}]
      [:div.p-10.b-1.b-rad-5.m-5.b-orange.hover-shadow
       {:class-name (s/join " " (conj
                                 (remove nil? [(if selected? "b-w-5")
                                               (if selectable? "pointer")
                                               (if (not selectable?) "opacity-5")])
                                 classes))
        :on-click select-fn}
       [:div.flex.align-items-c
        [:div.flex-grow-1
         [:div.flex.align-items-c
          (if multiselect?
            (checkbox selected? disable-checkbox?))
          (if icon [:div.m-r-5 (svg-icon icon 24)])
          [:span.f-w-b.f-s-1.flex-grow-1 name]
          (if help
            [show-info-button expanded?])]
         (if (and help @expanded?)
           [help-section help])
         (if (and content selected?)
           content)
         (if explanation-text
           [:div.i.f-s-12.f-w-n 
            explanation-text])]]])))

(defn skill-help [name key ability icon description]
  [:div
   [:div.flex.align-items-c
    (svg-icon icon 48)
    [:div.f-s-18.f-w-b.m-l-5
     [:div name]
     [:div.i (str "(" (:name (opt5e/abilities-map ability)) ")")]]]
   [:p description]])

(defn remaining-indicator [remaining & [size font-size]]
  [:span.bg-red.t-a-c.p-t-4.b-rad-50-p.inline-block.f-w-b
   (let [size (or size 18)
         font-size (or font-size 14)]
     {:class-name (str "h-" size " w-" size " f-s-" font-size)})
   remaining])

(defn actual-path [{:keys [::t/ref ::entity/path] :as selection}]
  (vec (if ref (if (sequential? ref) ref [ref]) path)))

(defn count-remaining [built-template character {:keys [::t/min ::t/max ::t/require-value?] :as selection}]
  (let [actual-path (actual-path selection)
        entity-path (entity/get-entity-path built-template character actual-path)
        selected-options (get-in character entity-path)
        selected-count (cond
                         (sequential? selected-options)
                         (count (if require-value?
                                  (filter ::entity/value selected-options)
                                  selected-options))
                         
                         (map? selected-options)
                         (if (or (::entity/value selected-options)
                                 (not require-value?))
                           1
                           0)
                         
                         :else 0)]
    (cond (< selected-count min)
          (- min selected-count)

          (and max (> selected-count max))
          (- max selected-count)

          :else
          0)))

(defn validate-selections [built-template character selections]
  (mapcat
   (fn [{:keys [::t/name ::t/tags] :as selection}]
     (if (not (tags :starting-equipment))
       (let [remaining (count-remaining built-template character selection)]
         (cond
           (pos? remaining) [(str "You have " remaining " more '" name "' selection" (if (> remaining 1) "s") " to make.")]
           (neg? remaining) [(str "You must remove " (Math/abs remaining) " '" name "' selection" (if (< remaining -1) "s") ".")]
           :else nil))))
   (entity/combine-selections selections)))

(defn new-option-selector [option-path
                           {:keys [::t/min ::t/max ::t/options ::t/multiselect?] :as selection}
                           disable-select-new?
                           {:keys [::t/key ::t/name ::t/path ::t/help ::t/selections ::t/prereqs
                                   ::t/modifiers ::t/select-fn ::t/ui-fn ::t/icon] :as option}]
  (let [built-char @(subscribe [:built-character])
        built-template @(subscribe [:built-template])
        character @(subscribe [:character])
        option-paths @(subscribe [:option-paths])
        new-option-path (conj (vec option-path) key)
        selected? (get-in option-paths new-option-path)
        failed-prereqs (reduce
                        (fn [failures {:keys [::t/prereq-fn ::t/label ::t/hide-if-fail?] :as prereq}]
                          (if (and prereq-fn (not (prereq-fn built-char)))
                            (conj failures prereq)
                            failures))
                        []
                        prereqs)
        meets-prereqs? (empty? failed-prereqs)
        selectable? (and (or selected?
                             meets-prereqs?)
                         (or (not disable-select-new?)
                             selected?))
        has-selections? (seq selections)
        named-modifiers (map (fn [{:keys [::mod/name ::mod/value]}]
                               (str name " " value))
                             (filter ::mod/name (flatten modifiers)))
        has-named-mods? (seq named-modifiers)
        modifiers-str (s/join ", " named-modifiers)]
    (if (not-any? ::t/hide-if-fail? failed-prereqs)
      ^{:key key}
      [option-selector-base {:name name
                             :key key
                             :help (if (or help has-named-mods?)
                                     [:div
                                      (if has-named-mods? [:div.i modifiers-str])
                                      [:div {:class-name (if has-named-mods? "m-t-5")} help]])
                             :selected? selected?
                             :selectable? selectable?
                             :option-path new-option-path
                             :multiselect? (or multiselect?
                                               (> min 1)
                                               (nil? max))
                             :select-fn (fn [e]
                                          (when (and (or (> max 1)
                                                         (nil? max)
                                                         multiselect?
                                                         (not selected?)
                                                         has-selections?)
                                                     (or selected?
                                                         meets-prereqs?)
                                                     selectable?)
                                            (let [updated-char (let [new-option {::entity/key key}]
                                                                 (if (or
                                                                      multiselect?
                                                                      (nil? max)
                                                                      (and
                                                                       (> min 1)
                                                                       (= min max)))
                                                                   (update-option
                                                                    built-template
                                                                    character
                                                                    option-path
                                                                    (fn [parent-vec]
                                                                      (if (or (nil? parent-vec) (map? parent-vec))
                                                                        [new-option]
                                                                        (let [parent-keys (into #{} (map ::entity/key) parent-vec)]
                                                                          (if (parent-keys key)
                                                                            (vec (remove #(= key (::entity/key %)) parent-vec))
                                                                            (conj parent-vec new-option))))))
                                                                   (update-option
                                                                    built-template
                                                                    character
                                                                    new-option-path
                                                                    (fn [o] (if multiselect? [new-option] new-option)))))]
                                              (dispatch [:set-character updated-char]))
                                            (if select-fn
                                              (select-fn (entity/get-option-value-path
                                                          built-template
                                                          character
                                                          new-option-path)
                                                         nil)))
                                          (.stopPropagation e))
                             :content (if (and (or selected? (= 1 (count options))) ui-fn)
                                        (ui-fn new-option-path built-template built-char))
                             :explanation-text (let [explanation-text (if (and (not meets-prereqs?)
                                                                               (not selected?))
                                                                        (s/join ", " (map ::t/label failed-prereqs)))]                      
                                                 explanation-text)
                             :icon icon}])))

(defn selection-section-title [title]
  [:span.m-l-5.f-s-18.f-w-b.flex-grow-1 title])

(defn selection-section-parent-title [title]
  [:span.i.f-s-14.f-w-n.m-l-5.m-b-2 title])

(defn remaining-component [max remaining]
  [:div.m-l-10
   (cond
     (pos? remaining)
     [:div.flex.align-items-c
      (remaining-indicator remaining)
      [:span.i.m-l-5 "remaining"]]

     (or (zero? remaining)
         (and (nil? max)
              (neg? remaining)))
     [:div.flex.align-items-c
      [:span.bg-green.t-a-c.w-18.h-18.p-t-2.b-rad-50-p.inline-block.f-w-b
       [:i.fa.fa-check.f-s-12]]
      [:span.i.m-l-5 "complete"]]

     (neg? remaining)
     [:div.flex.align-items-c
      [:span.i.m-r-5 "remove"]
      [:span.bg-red.t-a-c.w-18.h-18.p-t-4.b-rad-50-p.inline-block.f-w-b (Math/abs remaining)]])])

(defn selection-section-base []
  (let [expanded? (r/atom false)]
    (fn [{:keys [path parent-title name icon help max min remaining body]}]
      [:div.p-5.m-b-20.m-b-0-last
       (if parent-title
         (selection-section-parent-title parent-title))
       [:div.flex.align-items-c
        (if icon (svg-icon icon 24))
        (selection-section-title name)
        (if (and path help)
          [show-info-button expanded?])]
       (if (and help path @expanded?)
         [help-section help])
       (if (or (and (pos? min)
                    (nil? max))
               (= min max))
         (if (int? min)
           [:div.p-5.f-s-16
            [:div.flex.align-items-c.justify-cont-s-b
             [:span.i.m-r-10 (str "select " (if (= min max)
                                              min
                                              (str "at least " min)))]
             (remaining-component max remaining)]]))
       body])))

(def ignore-paths-ending-with #{:class :levels :asi-or-feat :ability-score-improvement})

(defn ancestor-names-string [built-template path]
  (let [ancestor-paths (map
                        (fn [p]
                          (if (ignore-paths-ending-with (last p))
                            []
                            p))
                        (reductions conj [] path))
        ancestors (map (fn [a-p]
                         (entity/get-in-lazy built-template
                                 (entity/get-template-selection-path built-template a-p [])))
                       (butlast ancestor-paths))
        ancestor-names (map ::t/name (remove nil? ancestors))]
    (s/join " - " ancestor-names)))

(defn selection-section [character built-char built-template option-paths ui-fns {:keys [::t/key ::t/name ::t/help ::t/options ::t/min ::t/max ::t/ref ::t/icon ::t/multiselect? ::entity/path ::entity/parent] :as selection}]
  (let [actual-path (actual-path selection)
        remaining (count-remaining built-template character selection)
        expanded? (r/atom false)]
    [selection-section-base {:path actual-path
                             :parent-title (if parent (ancestor-names-string built-template actual-path))
                             :name name
                             :icon icon
                             :help help
                             :max max
                             :min min
                             :remaining remaining
                             :body (if (and ui-fns (ui-fns (or ref key)))
                                     ((ui-fns (or ref key))
                                      {:character character
                                       :selection selection
                                       :built-char built-char})
                                     (doall
                                      (map-indexed
                                       (fn [i option]
                                         ^{:key i}
                                         [:div (new-option-selector actual-path
                                                                    selection
                                                                    (and (or (and max (> min 1))
                                                                             multiselect?)
                                                                         (not (pos? remaining))) option)])
                                       (sort-by ::t/name options))))}]))

(defn set-abilities! [abilities]
  (dispatch [:set-abilities abilities]))

(defn reroll-abilities []
  (dispatch [:set-abilities (char5e/standard-ability-rolls)]))

(defn set-standard-abilities []
  (fn []
    (dispatch [:set-abilities (char5e/abilities 15 14 13 12 10 8)])))

(defn reset-point-buy-abilities []
  (fn []
    (dispatch [:set-abilities (char5e/abilities 8 8 8 8 8 8)])))

(defn swap-abilities [i other-i k v]
  (stop-prop-fn
   (fn []
     (dispatch [:swap-ability-values i other-i k v]))))

(def ability-icons
  {:str "strong"
   :con "caduceus"
   :dex "body-balance"
   :int "read"
   :wis "meditation"
   :cha "aura"})

(defn ability-icon [k size]
  [:img {:class-name (str "h-" size " w-" size)
         :src (str "image/" (ability-icons k) ".svg")}])

(defn ability-subtitle [title]
  [:div.t-a-c.f-s-10.opacity-5 title])

(defn ability-modifier [v]
  [:div.f-6-12.f-w-n.h-24
   (ability-subtitle "mod")
   [:div.m-t--1
    (opt5e/ability-bonus-str v)]])

(defn ability-component [k v i controls]
  [:div.t-a-c
   [:div.f-s-18.f-w-b v]
   controls])

(def score-costs
  {8 0
   9 1
   10 2
   11 3
   12 4
   13 5
   14 7
   15 9})

(def point-buy-points 27)

(defn ability-value [v]
  [:div.f-s-18.f-w-b v])

(defn ability-increases-component [character built-char built-template asi-selections ability-keys]
  (let [total-abilities (es/entity-val built-char :abilities)]
    [:div
     (doall
      (map-indexed
       (fn [i {:keys [::t/name ::t/key ::t/min ::t/options ::t/different? ::entity/path] :as selection}]
         (let [increases-path (entity/get-entity-path built-template character path)
               selected-options (get-in character increases-path)
               ability-increases (frequencies (map ::entity/key selected-options))
               num-increased (apply + (vals ability-increases))
               num-remaining (- min num-increased)
               allowed-abilities (into #{} (map ::t/key options))
               ancestors-title (ancestor-names-string built-template path)]
           ^{:key i}
           [:div
            [:div.flex.justify-cont-s-a
             (doall (for [i (range 6)] ^{:key i} [:div.m-t-10 "+"]))]
            [:div.flex.justify-cont-s-b.m-t-10.align-items-c
             [:div
              [:div.m-l-5.i (str "Improvement: " ancestors-title)]]
             (remaining-component 2 num-remaining)]
            [:div.flex.justify-cont-s-a.m-t-5
             (doall
              (map-indexed
               (fn [i k]
                 (let [ability-disabled? (not (allowed-abilities k))
                       increase-disabled? (or ability-disabled?
                                              (zero? num-remaining)
                                              (and different? (pos? (ability-increases k)))
                                              (>= (total-abilities k) 20))
                       decrease-disabled? (or ability-disabled?
                                              (not (pos? (ability-increases k))))]
                   ^{:key k}
                   [:div.t-a-c
                    {:class-name (if ability-disabled? "opacity-5 cursor-disabled")}
                    [:div
                     {:class-name (if (and (not ability-disabled?)
                                           (zero? (ability-increases k 0)))
                                    "opacity-5")}
                     (ability-value (ability-increases k 0))] 
                    [:div.f-s-16
                     [:i.fa.fa-minus-circle.orange
                      {:class-name (if decrease-disabled? "opacity-5 cursor-disabled")
                       :on-click (stop-prop-fn
                                  (fn []
                                    (if (not decrease-disabled?)
                                      (dispatch [:decrease-ability-value increases-path k]))))}]
                     [:i.fa.fa-plus-circle.orange.m-l-5
                      {:class-name (if increase-disabled? "opacity-5 cursor-disabled")
                       :on-click (stop-prop-fn
                                  (fn []
                                    (if (not increase-disabled?)
                                      (dispatch [:increase-ability-value increases-path k]))))}]]]))
               ability-keys))]]))
       asi-selections))]))

(defn race-abilities-component [built-char ability-keys]
  (let [race-ability-increases (es/entity-val built-char :race-ability-increases)
        subrace-ability-increases (es/entity-val built-char :subrace-ability-increases)
        total-abilities (es/entity-val built-char :abilities)]
    [:div.flex.justify-cont-s-a
     (doall
      (map-indexed
       (fn [i k]
         ^{:key k}
         [:div.t-a-c
          (if (seq race-ability-increases)
            [:div
             [:div.m-t-10.m-b-10 "+"]
             (ability-subtitle "race")
             (let [race-v (get race-ability-increases k 0)]
               [:div
                {:class-name (if (zero? race-v)
                               "opacity-5")}
                (ability-value race-v)])])
          (if (seq subrace-ability-increases)
            [:div
             [:div.m-t-10.m-b-10 "+"]
             (ability-subtitle "subrace")
             (let [subrace-v (get subrace-ability-increases k 0)]
               [:div
                {:class-name (if (zero? subrace-v)
                               "opacity-5")}
                (ability-value subrace-v)])])])
       ability-keys))]))

(defn abilities-matrix-footer [built-char ability-keys]
  (let [total-abilities (es/entity-val built-char :abilities)]
    [:div
     (race-abilities-component built-char ability-keys)
     [:div.flex.justify-cont-s-a
      (doall
       (map-indexed
        (fn [i k]
          ^{:key k}
          [:div.t-a-c
           [:div.m-t-10.m-b-10 "="]
           (ability-subtitle "total")
           [:div.f-s-24.f-w-b (total-abilities k)]
           (ability-modifier (total-abilities k))])
        ability-keys))]]))

(defn abilities-header [ability-keys]
  [:div.flex.justify-cont-s-a
   (doall
    (map-indexed
     (fn [i k]
       ^{:key k}
       [:div.m-t-10.t-a-c
        (ability-icon k 24)
        [:div.uppercase (name k)]
        (ability-subtitle "base")])
     ability-keys))])

(defn abilities-component [character
                           built-char
                           built-template
                           asi-selections
                           content]
  (let [total-abilities (es/entity-val built-char :abilities)]
    [:div
     (abilities-header char5e/ability-keys)
     content
     (ability-increases-component character built-char built-template asi-selections char5e/ability-keys)
     (abilities-matrix-footer built-char char5e/ability-keys)]))


(defn point-buy-abilities [character built-char built-template asi-selections]
  (let [default-base-abilities (char5e/abilities 8 8 8 8 8 8)
        abilities (or (get-in character [::entity/options :ability-scores ::entity/value])
                      default-base-abilities)
        points-used (apply + (map (comp score-costs second) abilities))
        points-remaining (- point-buy-points points-used)
        total-abilities (es/entity-val built-char :abilities)]
    (abilities-component
     character
     built-char
     built-template
     asi-selections
     [:div
      [:div.flex.justify-cont-s-a
       (for [i (range 6)]
         ^{:key i}
         [:div
          (ability-value 8)
          [:div.m-t-10 "+"]])]
      [:div.flex.justify-cont-s-b.m-t-10.align-items-c
       [:div.m-l-5 "Point Buys"]
       (remaining-component 27 points-remaining)]
      [:div.flex.justify-cont-s-a
       (doall
        (map-indexed
         (fn [i [k v]]
           (let [increase-disabled? (or (zero? points-remaining)
                                        (= 15 v)
                                        (>= (total-abilities k) 20)
                                        (> (- (score-costs (inc v))
                                              (score-costs v))
                                           points-remaining))
                 decrease-disabled? (or (<= v 8) (>= points-remaining point-buy-points))]
             ^{:key k}
             [:div.t-a-c
              (ability-subtitle "bought")
              (ability-value (- v 8))
              [:div.f-s-11.f-w-b (str "(" (score-costs v) " pts)")]
              [:div.f-s-16
               [:i.fa.fa-minus-circle.orange
                {:class-name (if decrease-disabled? "opacity-5 cursor-disabled")
                 :on-click (stop-prop-fn
                            (fn [e]
                              (if (not decrease-disabled?)
                                (set-abilities! (update abilities k dec)))))}]
               [:i.fa.fa-plus-circle.orange.m-l-5
                {:class-name (if increase-disabled? "opacity-5 cursor-disabled")
                 :on-click (stop-prop-fn
                            (fn [_]
                              (if (not increase-disabled?) (set-abilities! (update abilities k inc)))))}]]]))
         abilities))]])))

(defn abilities-standard [character]
  [:div.flex.justify-cont-s-a
   (let [abilities (or (get-in character [::entity/options :ability-scores ::entity/value])
                       (char5e/abilities 15 14 13 12 10 8))
          abilities-vec (vec abilities)]
      (doall
       (map-indexed
        (fn [i [k v]]
          ^{:key k}
          [ability-component k v i
           [:div.f-s-16
            [:i.fa.fa-chevron-circle-left.orange
             {:on-click (swap-abilities i (dec i) k v)}]
            [:i.fa.fa-chevron-circle-right.orange.m-l-5
             {:on-click (swap-abilities i (inc i) k v)}]]])
        abilities-vec)))])

(defn abilities-roller [character built-char built-template asi-selections]
  (abilities-component
   character
   built-char
   built-template
   asi-selections
   [:div
    (abilities-standard character)
    [:button.form-button.m-t-5
     {:on-click (stop-prop-fn
                 (fn [e]
                   (reroll-abilities)))}
     "Re-Roll"]]))

(defn abilities-standard-editor [character built-char built-template asi-selections]
  (abilities-component
   character
   built-char
   built-template
   asi-selections
   (abilities-standard character)))

(defn abilities-entry [character built-char built-template asi-selections]
  (let [abilities (or (opt5e/get-raw-abilities character)
                      (char5e/abilities 15 14 13 12 10 8))
        total-abilities (es/entity-val built-char :abilities)]
    [:div
     (abilities-header char5e/ability-keys)
     [:div.flex.justify-cont-s-a
      (doall
       (map
        (fn [k]
          ^{:key k}
          [:div.p-1
           [:input.input.f-s-18.m-b-5.p-l-0
            {:value (k abilities)
             :on-change (fn [e] (let [value (.-value (.-target e))
                                      new-v (if (not (s/blank? value))
                                              (js/parseInt value))]
                                  (dispatch [:set-ability-score k new-v])))}]])
        char5e/ability-keys))]
     (ability-increases-component character built-char built-template asi-selections char5e/ability-keys)
     (race-abilities-component built-char char5e/ability-keys)
     [:div.flex.justify-cont-s-a
      (doall
       (map
        (fn [[k v]]
          ^{:key k}
          [:div.t-a-c.p-1
           [:div.m-t-10.m-b-10 "="]
           [:div.f-w-b "total"]
           [:input.input.b-3.f-s-18.m-b-5.p-l-0
            {:value (if (abilities k)
                      (total-abilities k))
             :on-change (fn [e] (let [total (total-abilities k)                                     
                                      value (.-value (.-target e))
                                      diff (- total
                                              (abilities k))
                                      new-v (if (not (s/blank? value))
                                              (- (js/parseInt value) (or diff 0)))]
                                  (dispatch [:set-ability-score k new-v])))}]])
        total-abilities))]]))

(defn ability-variant-option-selector [name key selected-key content & [select-fn]]
  [option-selector-base
   {:name name
    :key key
    :selected? (= selected-key key)
    :selectable? true
    :option-path [:ability-scores key]
    :content content
    :select-fn (fn [_]
                 (when (not= selected-key key)
                   (if select-fn (select-fn))
                   (dispatch [:set-ability-score-variant key])))}])

(defn abilities-editor [{:keys [character built-char built-template option-paths selections]}]
  [:div
   [:div.m-l-5 (selection-section-title "Ability Score Improvements")]
   (doall
    (map-indexed
     (fn [i {:keys [::t/key ::t/min ::t/max ::t/options ::entity/path] :as selection}]
       (let [remaining (count-remaining built-template character selection)]
         ^{:key i}
         [selection-section-base
          {:path path
           :parent-title (ancestor-names-string built-template path)
           :max 1
           :min 1
           :remaining remaining
           :body (doall
                  (map
                   (fn [option]
                     ^{:key (::t/key option)}
                     (new-option-selector path selection (and max (> min 1) (zero? remaining)) option))
                   (sort-by ::t/name options)))}]))
     (filter
      (fn [s]
        (= :asi-or-feat (::t/key s)))
      selections)))
   (let [asi-selections (filter (fn [s] (= :asi (::t/key s))) selections)
         selected-variant (get-in character [::entity/options :ability-scores ::entity/key])]
     [selection-section-base
      {:path [:ability-scores]
       :name "Abilities Variant"
       :min 1
       :max 1
       :body [:div
              (ability-variant-option-selector
               "Point Buy"
               :point-buy
               selected-variant
               (point-buy-abilities character built-char built-template asi-selections)
               #(set-abilities! (char5e/abilities 8 8 8 8 8 8)))
              (ability-variant-option-selector
               "Dice Roll"
               :standard-roll
               selected-variant
               (abilities-roller character built-char built-template asi-selections)
               #(reroll-abilities))
              (ability-variant-option-selector
               "Standard Scores"
               :standard-scores
               selected-variant
               (abilities-standard-editor character built-char built-template asi-selections)
               #(set-abilities! (char5e/abilities 15 14 13 12 10 8)))
              (ability-variant-option-selector
               "Manual Entry"
               :manual-entry
               selected-variant
               (abilities-entry character built-char built-template asi-selections))]}])])

(defn skills-selector [{:keys [character selection built-char]}]
  (let [{:keys [::t/ref ::t/max ::t/options]} selection
        path [::entity/options ref]
        selected-skills (get-in character path)
        selected-count (count selected-skills)
        remaining (- max selected-count)
        available-skills (into #{} (map ::t/key options))
        selected-skill-keys (into #{} (map ::entity/key selected-skills))]
    (doall
     (map
      (fn [{:keys [name key ability icon description]}]
        (let [skill-profs (es/entity-val built-char :skill-profs)
              has-prof? (and skill-profs (skill-profs key))
              selected? (selected-skill-keys key)
              selectable? (available-skills key)
              bad-selection? (and selected? (not selectable?))
              allow-select? (or selected?
                                (and (not selected?)
                                     (pos? remaining)
                                     selectable?
                                     (not has-prof?))
                                bad-selection?)]
          ^{:key key}
          [option-selector-base {:name name
                                 :key key
                                 :help (skill-help name key ability icon description)
                                 :selected? selected?
                                 :selectable? allow-select?
                                 :option-path [:skill-profs key]
                                 :select-fn (fn [_]
                                              (if allow-select?
                                                (dispatch [:select-skill path selected? key])))
                                 :explanation-text (if (and has-prof?
                                                            (not selected?))
                                                     "You already have this skill proficiency")
                                 :icon icon
                                 :classes (if bad-selection? "b-red")
                                 :multiselect? true}]))
      skill5e/skills))))

(def hit-points-headers
  [:tr.f-w-b.t-a-l
   [:th.p-5 "Level"]
   [:th.p-5 "Base"]
   [:th.p-5 "Con"]
   [:th.p-5 "Misc"]
   [:th.p-5 "Total"]])

(defn hp-selection-name-level [selection]
  (let [[_ class-kw _ level-kw _] (::entity/path selection)
        class-name (s/capitalize (name class-kw))]
    {:name class-name
     :level (js/parseInt (last (s/split (name level-kw) #"-")))
     :key class-kw}))

(defn hit-points-entry [character selections built-char built-template]
  (let [classes (es/entity-val built-char :classes)
        levels (es/entity-val built-char :levels)
        first-class (if levels (levels (first classes)))
        first-class-hit-die (:hit-die first-class)
        level-bonus (es/entity-val built-char :hit-point-level-bonus)
        con-bonus (:con (es/entity-val built-char :ability-bonuses))
        con-bonus-str (common/bonus-str con-bonus)
        misc-bonus (- level-bonus con-bonus)
        misc-bonus-str (common/bonus-str misc-bonus)
        all-level-values (map
                          (fn [selection]
                            (let [name-level (hp-selection-name-level selection)
                                  value (get-in character (entity/get-option-value-path built-template character (::entity/path selection)))]
                              {:name (str (:name name-level) (:level name-level))
                               :level (:level name-level)
                               :class (:key name-level)
                               :class-name (:name name-level)
                               :value value
                               :path (::entity/path selection)}))
                          (sort-by (fn [s] ((juxt :name :level)
                                            (hp-selection-name-level s))) selections))
        total-base-hps (apply + (:hit-die first-class) (map :value all-level-values))
        total-con-bonus (* con-bonus (inc (count selections)))
        total-misc-bonus (* misc-bonus (inc (count selections)))
        total-level-bonus (+ total-con-bonus total-misc-bonus)
        by-class (group-by :class all-level-values)
        total-hps (+ total-con-bonus total-misc-bonus total-base-hps)]
    [:div.m-t-5.p-5
     [:div.flex.align-items-c.justify-cont-s-b
      [:div.f-s-16.m-b-5
       [:span.f-w-b "Total:"]
       [:span.m-l-5
        (if (seq selections)
          [:input.input.w-70.b-3.f-w-b.f-s-16
           {:type :number
            :value total-hps
            :on-change (fn [e]
                         (let [value (js/parseInt (.. e -target -value))
                               total-value (if (js/isNaN value) 0 (- value first-class-hit-die total-level-bonus))
                               average-value (int (/ total-value (count selections)))
                               remainder (rem total-value (count selections))
                               first-selection (first selections)]
                           (doseq [selection selections]
                             (let [entity-path (entity/get-entity-path built-template character (::entity/path selection))]
                               (dispatch [:set-total-hps entity-path first-selection selection average-value remainder])))))}]
          total-hps)]]
      (if (seq selections)
        [:button.form-button.p-10
         {:on-click (fn [_]
                      (doseq [selection selections]
                        (let [[_ class-kw :as path] (::entity/path selection)]
                          (dispatch [:randomize-hit-points built-template path levels class-kw]))))}
         "Random"])
      (if (seq selections)
        [:button.form-button.p-10
         {:on-click (fn [_]
                      (doseq [selection selections]
                        (let [[_ class-kw :as path] (::entity/path selection)]
                          (dispatch [:set-hit-points-to-average built-template path levels class-kw]))))}
         "Average"])]
     (doall
      (map-indexed
       (fn [i cls]
         (let [level-values (by-class cls)
               total-base-hps (apply + (if (zero? i)
                                         (:hit-die first-class)
                                         0)
                                     (map :value level-values))
               num-level-values (count level-values)
               level-num (if (zero? i) (inc num-level-values) num-level-values)
               total-con-bonus (* con-bonus level-num)
               total-misc-bonus (* misc-bonus level-num)]
           ^{:key i}
           [:div.m-b-20
            [:div.f-s-16.m-l-5.f-w-b
             (str (s/capitalize (name cls))
                  " ("
                  "D"
                  (-> levels cls :hit-die) ")")]
            [:table.w-100-p.striped
             [:tbody
              hit-points-headers
              (if (zero? i)
                [:tr
                 [:td.p-5 1]
                 [:td.p-5 first-class-hit-die]
                 [:td.p-5 con-bonus-str]
                 [:td.p-5 misc-bonus-str]
                 [:td.p-5 (+ (:hit-die first-class) level-bonus)]])
              (doall
               (map-indexed
                (fn [j level-value]
                  ^{:key (:name level-value)}
                  [:tr
                   [:td.p-5 (:level level-value)]
                   [:td.p-5 [:input.input.m-t-0
                             {:type :number
                              :class-name (if (or (nil? (:value level-value))
                                                  (not (pos? (:value level-value))))
                                            "b-red b-3")
                              :on-change (fn [e]
                                           (let [value (js/parseInt (.. e -target -value))]
                                             (dispatch [:set-level-hit-points built-template character level-value value])))
                              :value (:value level-value)}]]
                   [:td.p-5 con-bonus-str]
                   [:td.p-5 misc-bonus-str]
                   [:td.p-5 (+ (:value level-value) level-bonus)]])
                level-values))
              [:tr
               [:td.p-5 "Total"]
               [:td.p-5 total-base-hps]
               [:td.p-5 (common/bonus-str total-con-bonus)]
               [:td.p-5 (common/bonus-str total-misc-bonus)]
               [:td.p-5 (+ total-base-hps total-con-bonus total-misc-bonus)]]]]]))
       classes))
     (if (> (count classes) 1)
       [:div.m-t-20
        [:div.f-s-16.m-l-5.f-w-b "Total"]
        [:table.w-100-p.striped
         [:tbody
          hit-points-headers
          [:tr
           [:td.p-5 "Total"]
           [:td.p-5 total-base-hps]
           [:td.p-5 (common/bonus-str total-con-bonus)]
           [:td.p-5 (common/bonus-str total-misc-bonus)]
           [:td.p-5 total-hps]]]]])]))

(defn sum-remaining [built-template character selections]
  (apply + (map #(Math/abs (count-remaining built-template character %)) selections)))

(defn hit-points-editor [{:keys [character built-char built-template option-paths selections]}]
  (let [num-selections (count selections)]
    (if (es/entity-val built-char :levels)
      [selection-section-base
       {:name "Hit Points"
        :min (if (pos? num-selections) num-selections)
        :max (if (pos? num-selections) num-selections)
        :remaining (if (pos? num-selections) (sum-remaining built-template character selections)) 
        :body (hit-points-entry character selections built-char built-template)}])))

(def pages
  [{:name "Race"
    :icon "woman-elf-face"
    :tags #{:race :subrace}}
   {:name "Ability Scores / Feats"
    :icon "strong"
    :tags #{:ability-scores :feats}
    :ui-fns [{:key :ability-scores :group? true :ui-fn abilities-editor}]
    }
   {:name "Background"
    :icon "ages"
    :tags #{:background}}
   {:name "Class"
    :icon "mounted-knight"
    :tags #{:class :subclass}
    :ui-fns [{:key :class :ui-fn class-levels-selector}
             {:key :hit-points :group? true :ui-fn hit-points-editor}]}
   {:name "Spells"
    :icon "spell-book"
    :tags #{:spells}}
   {:name "Proficiencies"
    :icon "juggler"
    :tags #{:profs}
    :ui-fns [{:key :skill-profs :ui-fn skills-selector}]}
   {:name "Equipment"
    :icon "backpack"
    :tags #{:equipment :starting-equipment}
    :ui-fns [{:key :weapons :ui-fn (partial inventory-selector weapon5e/weapons-map 60)}
             {:key :magic-weapons :ui-fn (partial inventory-selector mi5e/magic-weapon-map 60)}
             {:key :armor :ui-fn (partial inventory-selector armor5e/armor-map 60)}
             {:key :magic-armor :ui-fn (partial inventory-selector mi5e/magic-armor-map 60)}
             {:key :equipment :ui-fn #(inventory-selector equip5e/equipment-map 60 % :custom-equipment)}
             {:key :other-magic-items :ui-fn (partial inventory-selector mi5e/other-magic-item-map 60)}
             {:key :treasure :ui-fn #(inventory-selector equip5e/treasure-map 100 % :custom-treasure)}]}])

(defn section-tabs [available-selections built-template character page-index]
  [:div.flex.justify-cont-s-a
   (doall
    (map-indexed
     (fn [i {:keys [name icon tags]}]
       (let [selections (entity/tagged-selections available-selections tags)
             combined-selections (entity/combine-selections selections)
             total-remaining (sum-remaining built-template character combined-selections)]
         ^{:key name}
         [:div.p-5.hover-opacity-full.pointer
          {:class-name (if (= i page-index) "b-b-2 b-orange" "")
           :on-click (fn [_] (dispatch [:set-page i]))}
          [:div
           {:class-name (if (= i page-index) "selected-tab" "opacity-5 hover-opacity-full")}
           (svg-icon icon 32)]
          (if (not (= total-remaining 0))
            [:div.flex.justify-cont-end.m-t--10 (remaining-indicator total-remaining 12 11)])]))
     pages))])

(defn matches-group-fn [key]
  (fn [{s-key ::t/key tags ::t/tags}]
    (or (= key s-key)
        (get tags key))))

(defn matches-non-group-fn [key]
  #(if (or (= (::t/key %) key)
           (= (::t/ref %) key)) %))

(defn option-sources []
  (let [expanded? (r/atom false)]
    (fn []
      [:div.m-b-20
       [:div.flex.align-items-c
        (svg-icon "bookshelf")
        (selection-section-title "Option Sources")
        [expand-button "collapse" "select sources" expanded?]]
       (if @expanded?
         [:div
          [option-selector-base {:name "Player's Handbook"
                                 :help (t5e/amazon-frame-help t5e/phb-amazon-frame
                                                              [:span
                                                               "Base options are from the Player's Handbook, although descriptions are either from the "
                                                               t5e/srd-link
                                                               " or are OrcPub summaries. See the Player's Handbook for in-depth, official rules and descriptions."])
                                 :selected? true
                                 :selectable? true
                                 :multiselect? true
                                 :disable-checkbox? true}]
          (doall
           (map
            (fn [option]
              (new-option-selector [(::t/key t5e/optional-content-selection)]
                                   t5e/optional-content-selection
                                   false
                                   option))
            (::t/options t5e/optional-content-selection)))]
         [:div
          (doall
           (map-indexed
            (fn [i el]
              (with-meta el {:key i}))
            (interpose
             [:span.orange ", "]
             (map
              (fn [{:keys [name url]}]
                [:a.orange {:href url} name])
              (cons {:name "Player's Handbook"
                     :url disp5e/phb-url}
                    (map t5e/plugin-map @(subscribe [:selected-plugin-options])))))))])])))

(def selection-order-title
  (juxt ::t/order ::t/name ::entity/path))

(defn compare-selections [s1 s2]
  (< (selection-order-title s1)
     (selection-order-title s2)))

(defn new-options-column []
  (let [character @(subscribe [:character])
        built-char @(subscribe [:built-character])
        built-template @(subscribe [:built-template])
        available-selections @(subscribe [:available-selections])
        _ (if print-enabled? (js/console.log "AVAILABLE SELECTIONS" available-selections))
        page @(subscribe [:page])
        page-index (or page 0)
        option-paths @(subscribe [:option-paths])
        {:keys [tags ui-fns] :as page} (pages page-index)
        selections (entity/tagged-selections available-selections tags)
        combined-selections (entity/combine-selections selections)
        final-selections (remove #(and (zero? (::t/min %))
                                       (zero? (::t/max %))
                                       (zero? (count-remaining built-template character %)))
                                 combined-selections)]
    (if print-enabled? (js/console.log "FINAL SELECTIONS" (vec final-selections) (map ::t/key final-selections)))
    [:div.w-100-p
     [option-sources]
     [:div#options-column.b-1.b-rad-5
      [section-tabs available-selections built-template character page-index]
      [:div.flex.justify-cont-s-b.p-t-5.p-10.align-items-t
       [:button.form-button.p-5-10.m-r-5
        {:on-click
         (fn [_]
           (dispatch [:set-page (let [prev (dec page-index)]
                                  (if (neg? prev)
                                    (dec (count pages))
                                    prev))]))}
        "Back"]
       [:div.flex-grow-1
        [:h3.f-w-b.f-s-20.t-a-c (:name page)]]
       [:button.form-button.p-5-10.m-l-5
        {:on-click
         (fn [_]
           (dispatch [:set-page (let [next (inc page-index)]
                                  (if (>= next (count pages))
                                    0
                                    next))]))}
        "Next"]]
      (let [ui-fn-selections (mapcat
                              (fn [{:keys [key group? ui-fn]}]
                                (if group?
                                  (filter
                                   (matches-group-fn key)
                                   final-selections)
                                  [(some
                                    (matches-non-group-fn key)
                                    final-selections)]))
                              ui-fns)
            non-ui-fn-selections (sets/difference (set final-selections) (set ui-fn-selections))]
        [:div.p-5
         [:div
          (doall
           (map
            (fn [{:keys [key group? ui-fn]}]
              ^{:key key}
              [:div.m-t-20
               (if group?
                 (let [group (filter
                              (matches-group-fn key)
                              final-selections)]
                   (ui-fn {:character character
                           :built-char built-char
                           :built-template built-template
                           :option-paths option-paths
                           :selections group}))
                 (let [selection (some
                                  (matches-non-group-fn key)
                                  final-selections)]
                   (selection-section character built-char built-template option-paths {key ui-fn} selection)))])
            ui-fns))]
         (when (seq non-ui-fn-selections)
           [:div.m-t-20
            (doall
             (map
              (fn [selection]
                ^{:key (::entity/path selection)}
                [:div (selection-section character built-char built-template option-paths nil selection)])
              (into (sorted-set-by compare-selections) non-ui-fn-selections)))])])]]))

(defn random-sequential-selection [built-template character {:keys [::t/min ::t/max ::t/options ::entity/path] :as selection}]
  (let [num (inc (rand-int (count options)))
        actual-path (actual-path selection)]
    (update-option
     built-template
     character
     actual-path
     (fn [_]
       (mapv
        (fn [{:keys [::t/key]}]
          {::entity/key key})
        (take num options))))))

(defn random-selection [built-template character {:keys [::t/min ::t/max ::t/options ::t/multiselect? ::entity/path] :as selection}]
  (let [built-char (entity/build character built-template)
        new-options (take (count-remaining built-template character selection)
                          (shuffle (filter
                                    (partial meets-prereqs? built-char)
                                    options)))]
    (reduce
     (fn [new-character {:keys [::t/key]}]
       (let [new-option {::entity/key key}
             multiselect? (or multiselect?
                              (> min 1)
                              (nil? max))]
         (update-option
          built-template
          new-character
          (conj (actual-path selection) key)
          (fn [options] (if multiselect? (conj (or options []) new-option) new-option)))))
     character
     new-options)))

(def selection-randomizers
  {:ability-scores (fn [s _]
                     (fn [_] {::entity/key :standard-roll
                             ::entity/value (char5e/standard-ability-rolls)}))
   :hit-points (fn [{[_ class-kw] ::entity/path} built-char]
                 (fn [_]
                   (events5e/random-hit-points-option (char5e/levels built-char) class-kw)))})

(def max-iterations 100)

(defn random-character [current-character built-template]
  (reduce
   (fn [character i]
     (if (< i 10)
       (let [built-char (entity/build character built-template)
             available-selections (entity/available-selections character built-char built-template)
             combined-selections (entity/combine-selections available-selections)
             pending-selections (filter
                                 (fn [selection]
                                   (let [remaining (count-remaining built-template character selection)]
                                     (pos? remaining)))
                                 combined-selections)]
         (if (empty? pending-selections)
           (reduced character)
           (reduce
            (fn [new-character {:keys [::t/key ::t/sequential?] :as selection}]
              (let [selection-randomizer (selection-randomizers key)]
                (if selection-randomizer
                  (let [random-value (selection-randomizer selection)]
                    (update-option
                     built-template
                     new-character
                     (actual-path selection)
                     (selection-randomizer selection built-char)))
                    (if sequential?
                      (random-sequential-selection built-template new-character selection)
                      (random-selection built-template new-character selection)))))
            character
            pending-selections)))
       (reduced character)))
   {::entity/options (select-keys (::entity/options current-character) [:optional-content])}
   (range)))

(defn description-fields []
  (let [entity-values @(subscribe [:entity-values])]
    [:div.flex-grow-1.builder-column.personality-column
     [:div.m-t-5
      [:span.personality-label.f-s-18 "Character Name"]
      [character-input entity-values :character-name]]
     [:div.field
      [:span.personality-label.f-s-18 "Player Name"]
      [character-input entity-values :player-name]]
     [:div.flex.justify-cont-s-b
      [:div.field.flex-grow-1.m-r-2
       [:span.personality-label.f-s-18 "Age"]
       [character-input entity-values :age]]
      [:div.field.flex-grow-1.m-l-2.m-r-2
       [:span.personality-label.f-s-18 "Sex"]
       [character-input entity-values :sex]]
      [:div.field.flex-grow-1.m-l-2.m-r-2
       [:span.personality-label.f-s-18 "Height"]
       [character-input entity-values :height]]
      [:div.field.flex-grow-1.m-l-2
       [:span.personality-label.f-s-18 "Weight"]
       [character-input entity-values :weight]]]
     [:div.flex.justify-cont-s-b
      [:div.field.flex-grow-1.m-r-2
       [:span.personality-label.f-s-18 "Hair Color"]
       [character-input entity-values :hair]]
      [:div.field.flex-grow-1.m-1-2.m-r-2
       [:span.personality-label.f-s-18 "Eye Color"]
       [character-input entity-values :eyes]]
      [:div.field.flex-grow-1.m-1-2
       [:span.personality-label.f-s-18 "Skin Color"]
       [character-input entity-values :skin]]]
     [:div.field
      [:span.personality-label.f-s-18 "Personality Trait 1"]
      [character-textarea entity-values :personality-trait-1]]
     [:div.field
      [:span.personality-label.f-s-18 "Personality Trait 2"]
      [character-textarea entity-values :personality-trait-2]]
     [:div.field
      [:span.personality-label.f-s-18 "Ideals"]
      [character-textarea entity-values :ideals]]
     [:div.field
      [:span.personality-label.f-s-18 "Bonds"]
      [character-textarea entity-values :bonds]]
     [:div.field
      [:span.personality-label.f-s-18 "Flaws"]
      [character-textarea entity-values :flaws]]
     [:div.field
      [:span.personality-label.f-s-18 "Image URL"]
      [character-input entity-values :image-url]]
     [:div.field
      [:span.personality-label.f-s-18 "Description/Backstory"]
      [character-textarea entity-values :description "h-800"]]]))

(defn builder-columns []
  [:div.flex-grow-1.flex
   {:class-name (s/join " " (map #(str (name %) "-tab-active") @(subscribe [:active-tabs])))}
   [:div.builder-column.options-column
    [new-options-column]]
   [description-fields]
   [:div.builder-column.details-column
    [character-display]]])

(defn builder-tabs [active-tabs]
  [:div.hidden-lg.w-100-p
   [:div.builder-tabs
    [:span.builder-tab.options-tab
     {:class-name (if (active-tabs :options) "selected-builder-tab")
      :on-click (fn [_]
                  (dispatch [:set-active-tabs #{:build :options}]))} "Options"]
    [:span.builder-tab.personality-tab
     {:class-name (if (active-tabs :personality) "selected-builder-tab")
      :on-click (fn [_]
                  (dispatch [:set-active-tabs #{:build :personality}]))} "Description"]
    [:span.builder-tab.build-tab
     {:class-name (if (active-tabs :build) "selected-builder-tab")
      :on-click (fn [_]
                  (dispatch [:set-active-tabs #{:build :options}]))} "Build"]
    [:span.builder-tab.details-tab
     {:class-name (if (active-tabs :details) "selected-builder-tab")
      :on-click (fn [_]
                  (dispatch [:set-active-tabs #{:details}]))} "Details"]]])

(defn export-pdf [built-char]
  (fn [_]
    (let [field (.getElementById js/document "fields-input")]
      (aset field "value" (str (pdf-spec/make-spec built-char)))
      (.submit (.getElementById js/document "download-form")))))

(defn download-form [built-char]
  [:form.download-form
   {:id "download-form"
    :action (if (s/starts-with? js/window.location.href "http://localhost")
              "http://localhost:8890/character.pdf"
              "/character.pdf")
    :method "POST"
    :target "_blank"}
   [:input {:type "hidden" :name "body" :id "fields-input"}]])

(def patreon-link-props
  {:href "https://www.patreon.com/user?u=5892323" :target "_blank"})

(defn header [character built-char]
  [:div.w-100-p
   [:div.flex.align-items-c.justify-cont-s-b
    [:h1.f-s-36.f-w-b.m-t-21.m-b-19.m-l-10 "Character Builder"]
    [:div.flex.align-items-c.justify-cont-end.flex-wrap.m-r-10
     #_[:button.form-button.h-40.m-l-5.m-t-5.m-b-5
      {:class-name (if (<= (count @history) 1) "opacity-5")
       :on-click undo!}
      [:i.fa.fa-undo.f-s-18]
        [:span.m-l-5.hidden-sm.hidden-xs.hidden-md "Undo"]]
     [:button.form-button.h-40.m-l-5.m-t-5.m-b-5
      {:on-click (fn [_]
                   (do
                     (dispatch-sync [:set-loading true])
                     (let [new-char (random-character character @(subscribe [:built-template]))]
                       (dispatch [:set-character new-char]))))}
      [:span
       [:i.fa.fa-random.f-s-18]]
      [:span.m-l-5.header-button-text "Random"]]
     [:button.form-button.h-40.m-l-5.m-t-5.m-b-5
      {:on-click (fn [_] (dispatch [:reset-character]))}
      [:span
       [:i.fa.fa-undo.f-s-18]
       [:i.fa.fa-undo.f-s-18]]
      [:span.m-l-5.header-button-text "Reset"]]
     [:button.form-button.h-40.m-l-5.m-t-5.m-b-5
      {:on-click (export-pdf built-char)}
      [:i.fa.fa-print.f-s-18]
      [:span.m-l-5.header-button-text "Print"]]
     [:button.form-button.h-40.m-l-5.m-t-5.m-b-5
      [:i.fa.fa-floppy-o.f-s-18]
      [:span.m-l-5.header-button-text "Browser Save"]]
     [:button.form-button.h-40.m-l-5.opacity-5.m-t-5.m-b-5
      [:i.fa.fa-cloud-upload.f-s-18]
      [:span.m-l-5.header-button-text "Save" [:span.i.m-l-5 "(Coming Soon)"]]]]]])

(defn al-legality []
  (let [expanded? (r/atom false)]
    (fn [al-illegal-reasons used-resources]
      (let [num-resources (count (into #{} (map :resource-key used-resources)))
            multiple-resources? (> num-resources 1)
            al-legal? (and (empty? al-illegal-reasons)
                           (not multiple-resources?))]
        [:div.m-l-20.m-b-20
         [:div.flex
          [:div.i
           {:class-name
            (if al-legal?
              "green"
              "red")}
           [:i.fa.f-s-18
            {:class-name
             (if al-legal?
               "fa-check"
               "fa-times")}]
           [:a.m-l-5.f-w-b
            {:href "https://media.wizards.com/2016/dnd/downloads/AL_PH_SKT.pdf"}
            (str "Adventurer's League "
                 (if al-legal?
                   "Legal"
                   "Illegal"))]]
          (if (not al-legal?)
            [:span.m-l-10.f-s-14
             [expand-button "hide reasons" "show reasons" expanded?]])]
         (if (and @expanded?
                  (not al-legal?))
           [:div.i.red.m-t-5
            (map-indexed
             (fn [i reason]
               ^{:key i} [:div (str common/dot-char " " reason)])
             (if multiple-resources?
               (conj al-illegal-reasons
                     (str "You are only allowed to use content from one resource beyond the Player's Handbook, you are using "
                          num-resources
                          ": "
                          (s/join
                           ", "
                           (map
                            (fn [{:keys [resource-key option-name]}]
                              (str option-name " from " (:abbr (disp5e/sources resource-key))))
                            used-resources))))
               al-illegal-reasons))])]))))

(defn app-header []
  [:div#app-header.app-header.flex.flex-column.justify-cont-s-b
   [:div.app-header-bar.container
    [:div.content
     [:img.orcpub-logo {:src "image/orcpub-logo.svg"}]]]
   [:div.container.header-links
    [:div.content
     [:div
      [:div.m-l-10.white.hidden-xs.hidden-sm
       [:span "Questions? Comments? Issues? Feature Requests? We'd love to hear them, "]
       [:a {:href "https://muut.com/orcpub" :target :_blank} "report them here."]]
      [:div.hidden-xs.hidden-sm
       [:div.flex.align-items-c.f-w-b.f-s-18.m-t-10.m-l-10.white
        [:span.hidden-xs "Please support continuing development on "]
        [:a.m-l-5 patreon-link-props [:span "Patreon"]]
        [:a.m-l-5 patreon-link-props
         [:img.h-32.w-32 {:src "https://www.patreon.com/images/patreon_navigation_logo_mini_orange.png"}]]]]]]]])

(def loading-style
  {:position :absolute
   :height "100%"
   :width "100%"
   :top 0
   :bottom 0
   :right 0
   :left 0
   :z-index 100
   :background-color "rgba(0,0,0,0.6)"})

(defn character-builder []
  (let [character @(subscribe [:character])
        _  (if print-enabled? (cljs.pprint/pprint character))
        option-paths @(subscribe [:option-paths])
        built-template @(subscribe [:built-template])
        built-char @(subscribe [:built-character])
        active-tab @(subscribe [:active-tabs])
        view-width (.-width (gdom/getViewportSize js/window))
        plugins @(subscribe [:plugins])
        all-selections (entity/available-selections character built-char built-template)
        selection-validation-messages (validate-selections built-template character all-selections)
        al-illegal-reasons (concat (es/entity-val built-char :al-illegal-reasons)
                                   selection-validation-messages)
        used-resources (es/entity-val built-char :used-resources)
        loading @(subscribe [:loading])]
    (if print-enabled? (print-char built-char))
    [:div.app
     {:on-scroll (fn [e]
                   (let [app-header (js/document.getElementById "app-header")
                         header-height (.-offsetHeight app-header)
                         scroll-top (.-scrollTop (.-target e))
                         sticky-header (js/document.getElementById "sticky-header")
                         app-main (js/document.getElementById "app-main")
                         scrollbar-width (- js/window.innerWidth (.-offsetWidth app-main))
                         header-container (js/document.getElementById "header-container")]
                     (set! (.-paddingRight (.-style header-container)) (str scrollbar-width "px"))
                     (if (>= scroll-top header-height)
                       (set! (.-display (.-style sticky-header)) "block")
                       (set! (.-display (.-style sticky-header)) "none"))))}
     (if loading
       [:div {:style loading-style}
        [:div.flex.justify-cont-s-a.align-items-c.h-100-p
         [:img.h-200.w-200.m-t-200 {:src "/image/spiral.gif"}]]])
     [download-form built-char]
     [app-header]
     [:div
      [:div#sticky-header.sticky-header.w-100-p.posn-fixed
       [:div.flex.justify-cont-c.bg-light
        [:div#header-container.f-s-14.white.content
         [header character built-char]]]]
      [:div.flex.justify-cont-c.white
       [:div.content [header character built-char]]]
      [:div.container
       [:div.content
        [al-legality al-illegal-reasons used-resources]]]
      [:div.flex.justify-cont-c.white
       [:div.content [builder-tabs active-tab]]]
      [:div#app-main.flex.justify-cont-c.p-b-40
       [:div.f-s-14.white.content
        [:div.flex.w-100-p
         [builder-columns
          built-template
          built-char
          option-paths
          plugins
          active-tab
          all-selections]]]]
      [:div.white.flex.justify-cont-c
       [:div.content.f-w-n.f-s-12
        [:div.p-10
         [:div.m-b-5 "Icons made by Lorc, Caduceus, and Delapouite. Available on " [:a.orange {:href "http://game-icons.net"} "http://game-icons.net"]]]]]]]))
